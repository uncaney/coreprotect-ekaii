package net.coreprotect.database;

import java.util.Locale;

public enum Backend {
    SQLITE,
    MYSQL,
    POSTGRES;

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
            case "sqlite":
            case "file":
                return SQLITE;
            case "auto":
            case "":
            default:
                return legacyUseMysql ? MYSQL : SQLITE;
        }
    }

    public boolean isRemote() {
        return this != SQLITE;
    }

    public String displayName() {
        switch (this) {
            case POSTGRES: return "PostgreSQL";
            case MYSQL:    return "MySQL";
            case SQLITE:   return "SQLite";
            default:       return name();
        }
    }
}
