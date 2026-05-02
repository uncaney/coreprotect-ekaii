package net.coreprotect.database.dialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

import net.coreprotect.config.Config;
import net.coreprotect.database.Backend;

public final class SqliteDialect implements Dialect {

    @Override
    public Backend backend() { return Backend.SQLITE; }

    @Override
    public String driverClassName() { return "org.sqlite.JDBC"; }

    @Override
    public String jdbcUrl(String host, int port, String database) {
        // SQLite is file-based; ConfigHandler builds the actual URL.
        return null;
    }

    @Override
    public void beginTransaction(Statement statement) {
        try {
            statement.executeUpdate("BEGIN TRANSACTION");
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void commitTransaction(Statement statement) throws Exception {
        int count = 0;
        while (true) {
            try {
                statement.executeUpdate("COMMIT TRANSACTION");
                return;
            }
            catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().startsWith("[SQLITE_BUSY]") && count < 30) {
                    Thread.sleep(1000);
                    count++;
                    continue;
                }
                e.printStackTrace();
                return;
            }
        }
    }

    @Override
    public void performCheckpoint(Statement statement) throws SQLException {
        statement.executeUpdate("PRAGMA wal_checkpoint(TRUNCATE)");
    }

    @Override
    public void createSchema(Statement statement, String prefix) throws SQLException {
        if (!Config.getGlobal().DISABLE_WAL) {
            statement.executeUpdate("PRAGMA journal_mode=WAL;");
        }
        else {
            statement.executeUpdate("PRAGMA journal_mode=DELETE;");
        }

        statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "art_map (id INTEGER, art TEXT);");
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "block (time INTEGER, user INTEGER, wid INTEGER, x INTEGER, y INTEGER, z INTEGER, type INTEGER, data INTEGER, meta BLOB, blockdata BLOB, action INTEGER, rolled_back INTEGER);");
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "chat (time INTEGER, user INTEGER, wid INTEGER, x INTEGER, y INTEGER, z INTEGER, message TEXT);");
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "command (time INTEGER, user INTEGER, wid INTEGER, x INTEGER, y INTEGER, z INTEGER, message TEXT);");
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "container (time INTEGER, user INTEGER, wid INTEGER, x INTEGER, y INTEGER, z INTEGER, type INTEGER, data INTEGER, amount INTEGER, metadata BLOB, action INTEGER, rolled_back INTEGER);");
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "item (time INTEGER, user INTEGER, wid INTEGER, x INTEGER, y INTEGER, z INTEGER, type INTEGER, data BLOB, amount INTEGER, action INTEGER, rolled_back INTEGER);");
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "database_lock (status INTEGER, time INTEGER);");
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "entity (id INTEGER PRIMARY KEY ASC, time INTEGER, data BLOB);");
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "entity_map (id INTEGER, entity TEXT);");
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "material_map (id INTEGER, material TEXT);");
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "blockdata_map (id INTEGER, data TEXT);");
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "session (time INTEGER, user INTEGER, wid INTEGER, x INTEGER, y INTEGER, z INTEGER, action INTEGER);");
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "sign (time INTEGER, user INTEGER, wid INTEGER, x INTEGER, y INTEGER, z INTEGER, action INTEGER, color INTEGER, color_secondary INTEGER, data INTEGER, waxed INTEGER, face INTEGER, line_1 TEXT, line_2 TEXT, line_3 TEXT, line_4 TEXT, line_5 TEXT, line_6 TEXT, line_7 TEXT, line_8 TEXT);");
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "skull (id INTEGER PRIMARY KEY ASC, time INTEGER, owner TEXT, skin TEXT);");
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "user (id INTEGER PRIMARY KEY ASC, time INTEGER, user TEXT, uuid TEXT);");
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "username_log (id INTEGER PRIMARY KEY ASC, time INTEGER, uuid TEXT, user TEXT);");
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "version (time INTEGER, version TEXT);");
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "world (id INTEGER, world TEXT);");

        // Indices
        idx(statement, "art_map_id_index",         prefix + "art_map(id)");
        idx(statement, "block_index",              prefix + "block(wid,x,z,time)");
        idx(statement, "block_user_index",         prefix + "block(user,time)");
        idx(statement, "block_type_index",         prefix + "block(type,time)");
        idx(statement, "block_time_index",         prefix + "block(time)");
        idx(statement, "blockdata_map_id_index",   prefix + "blockdata_map(id)");
        idx(statement, "chat_index",               prefix + "chat(time)");
        idx(statement, "chat_user_index",          prefix + "chat(user,time)");
        idx(statement, "chat_wid_index",           prefix + "chat(wid,x,z,time)");
        idx(statement, "command_index",            prefix + "command(time)");
        idx(statement, "command_user_index",       prefix + "command(user,time)");
        idx(statement, "command_wid_index",        prefix + "command(wid,x,z,time)");
        idx(statement, "container_index",          prefix + "container(wid,x,z,time)");
        idx(statement, "container_user_index",     prefix + "container(user,time)");
        idx(statement, "container_type_index",     prefix + "container(type,time)");
        idx(statement, "container_time_index",     prefix + "container(time)");
        idx(statement, "item_index",               prefix + "item(wid,x,z,time)");
        idx(statement, "item_user_index",          prefix + "item(user,time)");
        idx(statement, "item_type_index",          prefix + "item(type,time)");
        idx(statement, "item_time_index",          prefix + "item(time)");
        idx(statement, "entity_map_id_index",      prefix + "entity_map(id)");
        idx(statement, "material_map_id_index",    prefix + "material_map(id)");
        idx(statement, "session_index",            prefix + "session(wid,x,z,time)");
        idx(statement, "session_action_index",     prefix + "session(action,time)");
        idx(statement, "session_user_index",       prefix + "session(user,time)");
        idx(statement, "session_time_index",       prefix + "session(time)");
        idx(statement, "sign_index",               prefix + "sign(wid,x,z,time)");
        idx(statement, "sign_user_index",          prefix + "sign(user,time)");
        idx(statement, "sign_time_index",          prefix + "sign(time)");
        idx(statement, "user_index",               prefix + "user(user)");
        idx(statement, "uuid_index",               prefix + "user(uuid)");
        idx(statement, "username_log_uuid_index",  prefix + "username_log(uuid,user)");
        idx(statement, "world_id_index",           prefix + "world(id)");
    }

    private static void idx(Statement s, String name, String cols) throws SQLException {
        s.executeUpdate("CREATE INDEX IF NOT EXISTS " + name + " ON " + cols + ";");
    }

    @Override
    public int purgeOldRows(Connection connection, String prefixedTable, long beforeUnixSeconds, int chunkLimit) throws SQLException {
        // SQLite doesn't support DELETE ... LIMIT without compile flag. Use a rowid sub-select.
        String sql = "DELETE FROM " + prefixedTable
                   + " WHERE rowid IN (SELECT rowid FROM " + prefixedTable
                   + " WHERE time < ? ORDER BY time ASC LIMIT ?)";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, beforeUnixSeconds);
            ps.setInt(2, chunkLimit);
            return ps.executeUpdate();
        }
    }

    @Override
    public boolean supportsReturningClause() {
        // SQLite 3.35+ supports RETURNING; CoreProtect already gates on
        // ConfigHandler.SERVER_VERSION >= 20 in Database.hasReturningKeys()
        // so we mirror the existing behaviour here.
        return true;
    }

    @Override
    public boolean supportsOnConflict() {
        return true;
    }
}
