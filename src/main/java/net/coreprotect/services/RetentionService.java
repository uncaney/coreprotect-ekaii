package net.coreprotect.services;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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

    /** Forces an immediate sweep, bypassing the cron schedule. */
    public Summary runNow() {
        if (keepSeconds <= 0) return Summary.skipped("retention-keep is 0/disabled");
        Dialect dialect = ConfigHandler.dialect();
        if (dialect == null) return Summary.skipped("no active dialect (DB not loaded)");
        long before = (System.currentTimeMillis() / 1000L) - keepSeconds;
        int totalDeleted = 0;
        long started = System.currentTimeMillis();
        for (String table : RETAINABLE_TABLES) {
            int deleted = purgeTable(dialect, table, before);
            totalDeleted += deleted;
        }
        lastRunUnix.set(System.currentTimeMillis() / 1000L);
        long elapsed = System.currentTimeMillis() - started;
        return Summary.success(totalDeleted, elapsed);
    }

    private void tick() {
        if (!enabled || keepSeconds <= 0 || cron == null) return;
        Instant now = Instant.now();
        long nowUnix = now.getEpochSecond();
        long lastRun = lastRunUnix.get();
        // Compute the most recent scheduled firing time at or before "now".
        // We approximate by asking next() from a point one minute ago; if the resulting
        // instant is in the past, we should run.
        Instant prevFire = cron.next(now.minusSeconds(60));
        if (prevFire.isAfter(now)) return; // not due yet
        // Don't double-fire within the same minute.
        if (lastRun > 0 && nowUnix - lastRun < 30) return;
        Summary s = runNow();
        Chat.sendConsoleMessage(Color.DARK_AQUA + "[CoreProtect] " + Color.WHITE + "Retention sweep: " + s);
    }

    private int purgeTable(Dialect dialect, String unprefixedTable, long beforeUnixSeconds) {
        String prefixed = ConfigHandler.prefix + unprefixedTable;
        Connection connection = null;
        int deleted = 0;
        try {
            connection = Database.getConnection(true, false, false, 5000);
            if (connection == null) return 0;
            connection.setAutoCommit(true);
            int loops = 0;
            while (deleted < MAX_ROWS_PER_TABLE_PER_RUN) {
                int n;
                try {
                    n = dialect.purgeOldRows(connection, prefixed, beforeUnixSeconds, CHUNK_LIMIT);
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
                if (loops > (MAX_ROWS_PER_TABLE_PER_RUN / CHUNK_LIMIT) + 8) break; // belt and braces
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
        public final long elapsedMs;
        public final String reason;
        private Summary(boolean ran, int deleted, long elapsedMs, String reason) {
            this.ran = ran; this.deleted = deleted; this.elapsedMs = elapsedMs; this.reason = reason;
        }
        public static Summary success(int deleted, long elapsedMs) {
            return new Summary(true, deleted, elapsedMs, null);
        }
        public static Summary skipped(String reason) {
            return new Summary(false, 0, 0, reason);
        }
        @Override
        public String toString() {
            if (!ran) return "skipped (" + reason + ")";
            return "deleted " + deleted + " row(s) in " + elapsedMs + " ms";
        }
    }
}
