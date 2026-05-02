package net.coreprotect.services;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import java.util.concurrent.TimeUnit;

import org.bukkit.Bukkit;

import net.coreprotect.CoreProtect;
import net.coreprotect.config.Config;
import net.coreprotect.config.ConfigHandler;
import net.coreprotect.database.Database;
import net.coreprotect.database.dialect.Dialect;
import net.coreprotect.utility.Chat;
import net.coreprotect.utility.Color;
import net.coreprotect.utility.HumanDuration;
import net.coreprotect.utility.MiniCron;

/**
 * Auto-retention sweeper. Runs on a cron schedule (default daily 04:00 UTC),
 * deleting rows older than {@code retention-keep} from every table that has
 * a {@code time} column. Backend-aware:
 * <ul>
 *   <li>Postgres &amp; MySQL: chunked DELETE with {@code LIMIT}, throttled to ~50 ms
 *       between chunks so the writer thread stays unblocked.</li>
 *   <li>SQLite: same chunked DELETE inside short transactions.</li>
 * </ul>
 *
 * <p>The service is opt-in (config key {@code retention-enabled: false} by
 * default) so existing operators see no change. Manual purge via
 * {@code /co purge t:&lt;time&gt;} continues to work regardless.</p>
 */
public final class RetentionService {

    private static final List<String> RETAINABLE_TABLES = Arrays.asList(
            "block", "container", "item", "chat", "command", "session", "sign", "skull", "entity", "username_log"
    );

    private static final int CHUNK_LIMIT = 5_000;
    private static final long INTER_CHUNK_SLEEP_MS = 50;
    /** Hard ceiling on rows per table per run — protects against a multi-hour first sweep. */
    private static final int MAX_ROWS_PER_TABLE_PER_RUN = 500_000;

    private static final AtomicReference<RetentionService> INSTANCE = new AtomicReference<>();
    private final AtomicLong lastRunUnix = new AtomicLong(0);
    private final ReentrantLock sweepLock = new ReentrantLock();
    private volatile MiniCron cron;
    private volatile long keepSeconds;
    private volatile boolean enabled;
    private volatile Object schedulerHandle;

    public static RetentionService get() {
        RetentionService s = INSTANCE.get();
        if (s == null) {
            s = new RetentionService();
            if (!INSTANCE.compareAndSet(null, s)) {
                s = INSTANCE.get();
            }
        }
        return s;
    }

    public void start(CoreProtect plugin) {
        reloadFromConfig();
        stop();
        if (!enabled || keepSeconds <= 0) {
            // Skip scheduling entirely when disabled — no need to wake up every minute.
            return;
        }
        Runnable tick = this::tick;
        if (ConfigHandler.isFolia) {
            // Folia exposes a per-thread async scheduler that supports fixed-rate tasks.
            schedulerHandle = Bukkit.getServer().getAsyncScheduler()
                    .runAtFixedRate(plugin, value -> tick.run(),
                            60_000L, 60_000L, TimeUnit.MILLISECONDS);
        }
        else {
            schedulerHandle = Bukkit.getScheduler()
                    .runTaskTimerAsynchronously(plugin, tick, 20L * 60, 20L * 60);
        }
    }

    public void stop() {
        Object h = schedulerHandle;
        schedulerHandle = null;
        if (h == null) return;
        try {
            // Both BukkitTask and ScheduledTask expose a cancel() method.
            h.getClass().getMethod("cancel").invoke(h);
        }
        catch (Throwable ignored) {}
    }

    public void reloadFromConfig() {
        Config c = Config.getGlobal();
        this.enabled = c.RETENTION_ENABLED;
        this.keepSeconds = HumanDuration.parseSeconds(c.RETENTION_KEEP);
        this.cron = new MiniCron(c.RETENTION_SCHEDULE);
    }

    public boolean isEnabled() { return enabled; }
    public long keepSeconds()  { return keepSeconds; }
    public long lastRunUnix()  { return lastRunUnix.get(); }
    public Instant nextFire(Instant now) { return cron == null ? null : cron.next(now); }

    public void setEnabled(boolean v) {
        this.enabled = v;
    }

    public void setKeep(String spec) {
        this.keepSeconds = HumanDuration.parseSeconds(spec);
    }

    /**
     * Forces an immediate sweep, bypassing the cron schedule. Mutex-protected:
     * if a previous sweep is still in flight (large keep delta on a busy server
     * can take more than the 60-second tick interval), the second caller is
     * told it overlapped instead of doubling up DELETEs against the same ctid set.
     *
     * <p>On Postgres with partitioning enabled, partitioned tables (block,
     * container) get O(1) DROP TABLE per stale week instead of chunked DELETE
     * — typically &lt;100 ms per partition vs minutes per million rows.</p>
     */
    public Summary runNow() {
        if (keepSeconds <= 0) return Summary.skipped("retention-keep is 0/disabled");
        Dialect dialect = ConfigHandler.dialect();
        if (dialect == null) return Summary.skipped("no active dialect (DB not loaded)");
        if (!sweepLock.tryLock()) {
            return Summary.skipped("another retention sweep is already in flight");
        }
        try {
            long before = (System.currentTimeMillis() / 1000L) - keepSeconds;
            int totalDeleted = 0;
            int partitionsDropped = 0;
            long started = System.currentTimeMillis();

            // Step 1: PG fast path — drop whole partitions older than retention window.
            if (PartitionService.isActive()) {
                try {
                    partitionsDropped = PartitionService.dropOlderThan(before);
                    // Also keep the upcoming-partition window healthy.
                    PartitionService.ensureUpcoming(4);
                }
                catch (Throwable t) {
                    t.printStackTrace();
                }
            }

            // Step 2: chunked DELETE for everything not handled by partition-drop.
            // When partitioning is active, block/container are skipped because their
            // *_default partition catches stragglers and the weekly partitions get DROPped.
            for (String table : RETAINABLE_TABLES) {
                if (PartitionService.isActive() && isPartitioned(table)) {
                    // The default partition can hold legacy rows; sweep just that one.
                    int deleted = purgeTable(dialect, table + "_default", before);
                    totalDeleted += deleted;
                    continue;
                }
                int deleted = purgeTable(dialect, table, before);
                totalDeleted += deleted;
            }
            lastRunUnix.set(System.currentTimeMillis() / 1000L);
            long elapsed = System.currentTimeMillis() - started;
            return Summary.success(totalDeleted, partitionsDropped, elapsed);
        }
        finally {
            sweepLock.unlock();
        }
    }

    private static boolean isPartitioned(String table) {
        for (String t : net.coreprotect.database.dialect.PostgresDialect.PARTITIONED_TABLES) {
            if (t.equals(table)) return true;
        }
        return false;
    }

    private void tick() {
        if (!enabled || keepSeconds <= 0 || cron == null) return;
        Instant now = Instant.now();
        long nowUnix = now.getEpochSecond();
        long lastRun = lastRunUnix.get();
        // Compute the most recent scheduled firing time at or before "now".
        Instant prevFire = cron.next(now.minusSeconds(60));
        if (prevFire.isAfter(now)) return; // not due yet
        if (lastRun > 0 && nowUnix - lastRun < 30) return;
        // Skip if a sweep is already running (held by /co retention run, or a slow prior tick).
        if (sweepLock.isLocked()) return;
        Summary s = runNow();
        Chat.sendConsoleMessage(Color.DARK_AQUA + "[CoreProtect] " + Color.WHITE + "Retention sweep: " + s);
    }

    /**
     * Acquire a fresh connection per chunk-batch. Hikari's default
     * {@code maxLifetime=60_000ms} (set in ConfigHandler#openHikariFor) would
     * evict the connection mid-sweep on a busy table; rotating sidesteps that
     * and gives PG/MySQL a chance to break long DELETE locks between batches.
     * The cost (one Hikari acquire per ~10 chunks) is negligible — the inner
     * 50 ms inter-chunk sleep dominates.
     */
    private int purgeTable(Dialect dialect, String unprefixedTable, long beforeUnixSeconds) {
        String prefixed = ConfigHandler.prefix + unprefixedTable;
        int deleted = 0;
        int chunksThisConnection = 0;
        Connection connection = null;
        try {
            int loops = 0;
            while (deleted < MAX_ROWS_PER_TABLE_PER_RUN) {
                if (connection == null || chunksThisConnection >= 10) {
                    if (connection != null) {
                        try { connection.close(); } catch (SQLException ignored) {}
                    }
                    connection = Database.getConnection(true, false, false, 5000);
                    chunksThisConnection = 0;
                    if (connection == null) return deleted;
                    connection.setAutoCommit(true);
                }
                int n;
                try {
                    n = dialect.purgeOldRows(connection, prefixed, beforeUnixSeconds, CHUNK_LIMIT);
                    chunksThisConnection++;
                }
                catch (SQLException tableMissing) {
                    // Table may not exist yet (e.g., fresh install); skip silently.
                    return deleted;
                }
                if (n <= 0) break;
                deleted += n;
                loops++;
                try { Thread.sleep(INTER_CHUNK_SLEEP_MS); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (loops > (MAX_ROWS_PER_TABLE_PER_RUN / CHUNK_LIMIT) + 8) break;
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
        finally {
            if (connection != null) {
                try { connection.close(); } catch (SQLException ignored) {}
            }
        }
        return deleted;
    }

    /** Lightweight summary for the console. */
    public static final class Summary {
        public final boolean ran;
        public final int deleted;
        public final int partitionsDropped;
        public final long elapsedMs;
        public final String reason;
        private Summary(boolean ran, int deleted, int partitionsDropped, long elapsedMs, String reason) {
            this.ran = ran; this.deleted = deleted; this.partitionsDropped = partitionsDropped;
            this.elapsedMs = elapsedMs; this.reason = reason;
        }
        public static Summary success(int deleted, int partitionsDropped, long elapsedMs) {
            return new Summary(true, deleted, partitionsDropped, elapsedMs, null);
        }
        public static Summary skipped(String reason) {
            return new Summary(false, 0, 0, 0, reason);
        }
        @Override
        public String toString() {
            if (!ran) return "skipped (" + reason + ")";
            StringBuilder sb = new StringBuilder();
            if (partitionsDropped > 0) {
                sb.append("dropped ").append(partitionsDropped).append(" partition(s)");
            }
            if (deleted > 0) {
                if (sb.length() > 0) sb.append(", ");
                sb.append("deleted ").append(deleted).append(" row(s)");
            }
            if (sb.length() == 0) sb.append("nothing to do");
            sb.append(" in ").append(elapsedMs).append(" ms");
            return sb.toString();
        }
    }
}
