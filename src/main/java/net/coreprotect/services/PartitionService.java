package net.coreprotect.services;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.coreprotect.config.Config;
import net.coreprotect.config.ConfigHandler;
import net.coreprotect.database.Backend;
import net.coreprotect.database.Database;
import net.coreprotect.database.dialect.PostgresDialect;

/**
 * Manages PostgreSQL weekly range partitions for {@link PostgresDialect#PARTITIONED_TABLES}.
 *
 * <p>Two operations:</p>
 * <ul>
 *   <li>{@link #ensureUpcoming(int)} — make sure the parent table has weekly
 *       partitions for the current ISO week and the next N weeks. Idempotent
 *       (CREATE TABLE IF NOT EXISTS PARTITION OF ... FOR VALUES FROM ... TO ...).</li>
 *   <li>{@link #dropOlderThan(long)} — DROP every partition whose upper bound
 *       is strictly less than the given epoch second. O(1) per partition; the
 *       on-disk space is reclaimed immediately, no VACUUM needed.</li>
 * </ul>
 *
 * <p>Partition naming: {@code <prefix><table>_y<YYYY>w<NN>}, e.g.
 * {@code co_block_y2026w17}. The week is ISO-week (Mon=1).</p>
 *
 * <p>This service is a no-op on SQLite/MySQL or when {@code postgres-partitioning}
 * is disabled — callers should still invoke it; it shorts itself out.</p>
 */
public final class PartitionService {

    private PartitionService() {}

    private static final DateTimeFormatter WEEK_FMT  = DateTimeFormatter.ofPattern("'y'yyyy'w'ww");
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("'y'yyyy'm'MM");
    private static final Pattern PART_NAME = Pattern.compile("^(.+)_y(\\d{4})[wm](\\d{2})$");

    private static boolean isMonthly() {
        String iv = Config.getGlobal().POSTGRES_PARTITION_INTERVAL;
        return iv != null && iv.trim().equalsIgnoreCase("monthly");
    }

    /** Should we be doing partition work at all? */
    public static boolean isActive() {
        return ConfigHandler.backend() == Backend.POSTGRES
                && Config.getGlobal().POSTGRES_PARTITIONING;
    }

    /** Ensure partitions covering {@code now} + {@code lookahead} intervals exist. Interval = week or month per config. */
    public static int ensureUpcoming(int lookahead) {
        if (!isActive()) return 0;
        int created = 0;
        boolean monthly = isMonthly();
        Connection c = null;
        try {
            c = Database.getConnection(true, false, false, 5000);
            if (c == null) return 0;
            c.setAutoCommit(true);
            try (Statement s = c.createStatement()) {
                ZonedDateTime base = ZonedDateTime.now(ZoneOffset.UTC);
                for (int w = 0; w <= lookahead; w++) {
                    ZonedDateTime weekStart;
                    ZonedDateTime weekEnd;
                    String suffix;
                    if (monthly) {
                        ZonedDateTime monthStart = base.plusMonths(w).withDayOfMonth(1)
                                .withHour(0).withMinute(0).withSecond(0).withNano(0);
                        weekStart = monthStart;
                        weekEnd = monthStart.plusMonths(1);
                        suffix = "_" + monthStart.format(MONTH_FMT);
                    }
                    else {
                        weekStart = startOfIsoWeek(base.plusWeeks(w));
                        weekEnd = weekStart.plusWeeks(1);
                        suffix = "_" + weekStart.format(WEEK_FMT);
                    }
                    long fromUnix = weekStart.toEpochSecond();
                    long toUnix = weekEnd.toEpochSecond();
                    for (String t : PostgresDialect.PARTITIONED_TABLES) {
                        String parent = ConfigHandler.prefix + t;
                        String child = parent + suffix;
                        try {
                            s.executeUpdate("CREATE TABLE IF NOT EXISTS " + child
                                    + " PARTITION OF " + parent
                                    + " FOR VALUES FROM (" + fromUnix + ") TO (" + toUnix + ")");
                            created++;
                        }
                        catch (SQLException e) {
                            // Most likely a constraint conflict because rows already landed in
                            // *_default for this range. Detach default → create child → reattach default.
                            // Keep the recovery path narrow: only detach if message says overlap.
                            String msg = e.getMessage() == null ? "" : e.getMessage();
                            if (msg.contains("would overlap") || msg.contains("default partition")) {
                                if (recoverViaDefault(s, parent, child, fromUnix, toUnix)) {
                                    created++;
                                }
                            }
                        }
                    }
                }
            }
        }
        catch (SQLException e) {
            e.printStackTrace();
        }
        finally {
            if (c != null) try { c.close(); } catch (SQLException ignored) {}
        }
        return created;
    }

    /**
     * Drop partitions whose upper-bound is strictly less than {@code beforeUnixSeconds}.
     *
     * @return total number of partitions dropped across all tracked tables
     */
    public static int dropOlderThan(long beforeUnixSeconds) {
        if (!isActive()) return 0;
        int dropped = 0;
        Connection c = null;
        try {
            c = Database.getConnection(true, false, false, 5000);
            if (c == null) return 0;
            c.setAutoCommit(true);
            try (Statement s = c.createStatement()) {
                for (String t : PostgresDialect.PARTITIONED_TABLES) {
                    String parent = ConfigHandler.prefix + t;
                    for (PartitionInfo p : listPartitions(s, parent)) {
                        if (p.upperUnix > 0 && p.upperUnix <= beforeUnixSeconds) {
                            try {
                                s.executeUpdate("DROP TABLE IF EXISTS " + p.fullName);
                                dropped++;
                            }
                            catch (SQLException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                }
            }
        }
        catch (SQLException e) {
            e.printStackTrace();
        }
        finally {
            if (c != null) try { c.close(); } catch (SQLException ignored) {}
        }
        return dropped;
    }

    private static boolean recoverViaDefault(Statement s, String parent, String child, long fromUnix, long toUnix) {
        String defaultPart = parent + "_default";
        try {
            s.executeUpdate("ALTER TABLE " + parent + " DETACH PARTITION " + defaultPart);
            try {
                s.executeUpdate("CREATE TABLE IF NOT EXISTS " + child
                        + " PARTITION OF " + parent
                        + " FOR VALUES FROM (" + fromUnix + ") TO (" + toUnix + ")");
            }
            finally {
                try {
                    s.executeUpdate("ALTER TABLE " + parent + " ATTACH PARTITION " + defaultPart + " DEFAULT");
                }
                catch (SQLException ignored) {}
            }
            return true;
        }
        catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    private static ZonedDateTime startOfIsoWeek(ZonedDateTime z) {
        // Monday 00:00 UTC of the ISO week containing z.
        int dow = z.getDayOfWeek().getValue(); // Mon=1..Sun=7
        ZonedDateTime mon = z.minusDays(dow - 1L);
        return ZonedDateTime.of(LocalDate.of(mon.getYear(), mon.getMonth(), mon.getDayOfMonth()).atStartOfDay(), ZoneOffset.UTC);
    }

    private static final class PartitionInfo {
        final String fullName;
        final long upperUnix; // 0 = unknown / default partition

        PartitionInfo(String fullName, long upperUnix) {
            this.fullName = fullName;
            this.upperUnix = upperUnix;
        }
    }

    private static List<PartitionInfo> listPartitions(Statement s, String parentTable) throws SQLException {
        // pg_inherits + pg_class to find all partitions and their bound expressions.
        String parentTableNoSchema = parentTable.contains(".") ? parentTable.substring(parentTable.indexOf('.') + 1) : parentTable;
        String sql = "SELECT n.nspname, c.relname, pg_get_expr(c.relpartbound, c.oid) AS bounds "
                   + "FROM pg_inherits i "
                   + "JOIN pg_class p ON p.oid = i.inhparent "
                   + "JOIN pg_class c ON c.oid = i.inhrelid "
                   + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                   + "WHERE p.relname = '" + parentTableNoSchema.replace("'", "''") + "'";
        List<PartitionInfo> out = new ArrayList<>();
        try (ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                String name = rs.getString(2);
                String bounds = rs.getString(3);
                long upper = 0;
                if (bounds != null) {
                    // Bounds string format: "FOR VALUES FROM ('123') TO ('456')" or "DEFAULT"
                    if (!bounds.toUpperCase().contains("DEFAULT")) {
                        Matcher m = Pattern.compile("TO\\s*\\(\\s*'?(\\d+)'?\\s*\\)").matcher(bounds);
                        if (m.find()) {
                            try { upper = Long.parseLong(m.group(1)); }
                            catch (NumberFormatException ignored) {}
                        }
                    }
                }
                out.add(new PartitionInfo(name, upper));
            }
        }
        return out;
    }

    /** For tests / diagnostics: parse a partition name into {table, year, week}. */
    public static String describePartitionSuffix(Instant t) {
        return ZonedDateTime.ofInstant(t, ZoneOffset.UTC).format(isMonthly() ? MONTH_FMT : WEEK_FMT);
    }

    /** Names of partitions currently attached to the parent. Used by retention to know what's there. */
    public static Set<String> listAttachedPartitionNames(String parentTable) {
        if (!isActive()) return new HashSet<>();
        Set<String> out = new HashSet<>();
        Connection c = null;
        try {
            c = Database.getConnection(true, false, false, 5000);
            if (c == null) return out;
            try (Statement s = c.createStatement()) {
                for (PartitionInfo p : listPartitions(s, parentTable)) {
                    out.add(p.fullName);
                }
            }
        }
        catch (SQLException e) {
            e.printStackTrace();
        }
        finally {
            if (c != null) try { c.close(); } catch (SQLException ignored) {}
        }
        return out;
    }
}
