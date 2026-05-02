package net.coreprotect.database;

import java.util.Locale;

public enum Backend {
    SQLITE,
    MYSQL,
    POSTGRES,
    DUCKDB;

    public static Backend resolve(String configured, boolean legacyUseMysql) {
        if (configured == null) {
            configured = "auto";
        }
        switch (configured.trim().toLowerCase(Locale.ROOT)) {
            case "postgres":
            case "postgresql":
            case "pg":
                return POSTGRES;
            case "mysql":
            case "mariadb":
                return MYSQL;
            case "duckdb":
            case "duck":
                return DUCKDB;
            case "sqlite":
            case "file":
                return SQLITE;
            case "auto":
            case "":
            default:
                return legacyUseMysql ? MYSQL : SQLITE;
        }
    }

    /** True for backends that go through HikariCP. SQLite + DuckDB are file/embedded — no pool. */
    public boolean isRemote() {
        return this == MYSQL || this == POSTGRES;
    }

    public String displayName() {
        switch (this) {
            case POSTGRES: return "PostgreSQL";
            case MYSQL:    return "MySQL";
            case DUCKDB:   return "DuckDB";
            case SQLITE:   return "SQLite";
            default:       return name();
        }
    }
}
