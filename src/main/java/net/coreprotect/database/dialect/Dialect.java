package net.coreprotect.database.dialect;

import java.sql.SQLException;
import java.sql.Statement;

import net.coreprotect.database.Backend;

/**
 * Database-specific behaviour CoreProtect needs to know about.
 *
 * <p>The bulk of CoreProtect's runtime SQL (INSERT statements, range scans,
 * lookup queries) is plain ANSI SQL and runs unchanged on any of the three
 * supported backends. The differences live in (a) DDL, (b) transaction
 * keywords, (c) WAL/checkpoint pragmas, (d) retention deletion strategy,
 * and (e) dictionary upserts. Those are the only knobs this interface
 * exposes.</p>
 */
public interface Dialect {

    Backend backend();

    /**
     * @return JDBC driver class name to register before opening a connection.
     *         null for SQLite (handled by org.sqlite.JDBC bundled in the jar).
     */
    String driverClassName();

    /** Optional fallback driver class for legacy MySQL builds; null otherwise. */
    default String fallbackDriverClassName() { return null; }

    /**
     * Build the JDBC URL for this backend. SQLite ignores host/port/db and
     * uses the file path stored in {@code ConfigHandler.path + ConfigHandler.sqlite}.
     */
    String jdbcUrl(String host, int port, String database);

    /** Begin a transaction on the given statement; logs and swallows errors like the legacy code. */
    void beginTransaction(Statement statement);

    /** Commit a transaction. Mirrors {@code Database.commitTransaction}'s semantics. */
    void commitTransaction(Statement statement) throws Exception;

    /** SQLite WAL checkpoint (no-op on remote DBs). */
    void performCheckpoint(Statement statement) throws SQLException;

    /**
     * Create every CoreProtect table + index for the given prefix.
     * Idempotent: existing tables are left alone.
     */
    void createSchema(Statement statement, String prefix) throws SQLException;

    /**
     * Delete rows older than {@code beforeUnixSeconds} from the given (prefixed)
     * table, capped at {@code chunkLimit} rows in a single statement.
     *
     * @return the number of rows deleted (so the caller can loop until 0).
     */
    int purgeOldRows(java.sql.Connection connection, String prefixedTable, long beforeUnixSeconds, int chunkLimit) throws SQLException;

    /**
     * @return true if {@code INSERT ... RETURNING <pk>} is supported natively
     *         (PostgreSQL: always, SQLite: 3.35+, MySQL: never).
     */
    boolean supportsReturningClause();

    /**
     * @return true if {@code INSERT ... ON CONFLICT DO NOTHING} is supported
     *         natively (PG: yes, SQLite 3.24+: yes, MySQL: no — must use
     *         INSERT IGNORE or ON DUPLICATE KEY UPDATE).
     */
    boolean supportsOnConflict();
}
