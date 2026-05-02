package net.coreprotect.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import net.coreprotect.config.Config;
import net.coreprotect.config.ConfigHandler;
import net.coreprotect.consumer.Consumer;
import net.coreprotect.consumer.Queue;
import net.coreprotect.consumer.process.Process;
import net.coreprotect.database.dialect.Dialect;
import net.coreprotect.language.Phrase;
import net.coreprotect.model.BlockGroup;
import net.coreprotect.utility.Chat;
import net.coreprotect.utility.Color;
import net.coreprotect.utility.ItemUtils;
import net.coreprotect.utility.MaterialUtils;

public class Database extends Queue {

    public static final int SIGN = 0;
    public static final int BLOCK = 1;
    public static final int SKULL = 2;
    public static final int CONTAINER = 3;
    public static final int WORLD = 4;
    public static final int CHAT = 5;
    public static final int COMMAND = 6;
    public static final int SESSION = 7;
    public static final int ENTITY = 8;
    public static final int MATERIAL = 9;
    public static final int ART = 10;
    public static final int ENTITY_MAP = 11;
    public static final int BLOCKDATA = 12;
    public static final int ITEM = 13;

    private static final Map<Integer, String> SQL_QUERIES = new HashMap<>();

    static {
        // Initialize SQL queries for different table types
        SQL_QUERIES.put(SIGN, "INSERT INTO %sprefix%sign (time, user, wid, x, y, z, action, color, color_secondary, data, waxed, face, line_1, line_2, line_3, line_4, line_5, line_6, line_7, line_8) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
        SQL_QUERIES.put(BLOCK, "INSERT INTO %sprefix%block (time, user, wid, x, y, z, type, data, meta, blockdata, action, rolled_back) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
        SQL_QUERIES.put(SKULL, "INSERT INTO %sprefix%skull (time, owner, skin) VALUES (?, ?, ?)");
        SQL_QUERIES.put(CONTAINER, "INSERT INTO %sprefix%container (time, user, wid, x, y, z, type, data, amount, metadata, action, rolled_back) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
        SQL_QUERIES.put(ITEM, "INSERT INTO %sprefix%item (time, user, wid, x, y, z, type, data, amount, action, rolled_back) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
        SQL_QUERIES.put(WORLD, "INSERT INTO %sprefix%world (id, world) VALUES (?, ?)");
        SQL_QUERIES.put(CHAT, "INSERT INTO %sprefix%chat (time, user, wid, x, y, z, message) VALUES (?, ?, ?, ?, ?, ?, ?)");
        SQL_QUERIES.put(COMMAND, "INSERT INTO %sprefix%command (time, user, wid, x, y, z, message) VALUES (?, ?, ?, ?, ?, ?, ?)");
        SQL_QUERIES.put(SESSION, "INSERT INTO %sprefix%session (time, user, wid, x, y, z, action) VALUES (?, ?, ?, ?, ?, ?, ?)");
        SQL_QUERIES.put(ENTITY, "INSERT INTO %sprefix%entity (time, data) VALUES (?, ?)");
        SQL_QUERIES.put(MATERIAL, "INSERT INTO %sprefix%material_map (id, material) VALUES (?, ?)");
        SQL_QUERIES.put(ART, "INSERT INTO %sprefix%art_map (id, art) VALUES (?, ?)");
        SQL_QUERIES.put(ENTITY_MAP, "INSERT INTO %sprefix%entity_map (id, entity) VALUES (?, ?)");
        SQL_QUERIES.put(BLOCKDATA, "INSERT INTO %sprefix%blockdata_map (id, data) VALUES (?, ?)");
    }

    public static void beginTransaction(Statement statement, boolean isMySQL) {
        Consumer.transacting = true;
        Dialect dialect = ConfigHandler.dialect();
        if (dialect != null) {
            dialect.beginTransaction(statement);
            return;
        }
        try {
            statement.executeUpdate(isMySQL ? "START TRANSACTION" : "BEGIN TRANSACTION");
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void commitTransaction(Statement statement, boolean isMySQL) throws Exception {
        Dialect dialect = ConfigHandler.dialect();
        if (dialect != null) {
            try {
                dialect.commitTransaction(statement);
            }
            finally {
                Consumer.transacting = false;
                Consumer.interrupt = false;
            }
            return;
        }
        // Legacy fallback (should not happen in practice).
        int count = 0;
        while (true) {
            try {
                statement.executeUpdate(isMySQL ? "COMMIT" : "COMMIT TRANSACTION");
            }
            catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().startsWith("[SQLITE_BUSY]") && count < 30) {
                    Thread.sleep(1000);
                    count++;
                    continue;
                }
                e.printStackTrace();
            }
            Consumer.transacting = false;
            Consumer.interrupt = false;
            return;
        }
    }

    public static void performCheckpoint(Statement statement, boolean isMySQL) throws SQLException {
        Dialect dialect = ConfigHandler.dialect();
        if (dialect != null) {
            dialect.performCheckpoint(statement);
            return;
        }
        if (!isMySQL) {
            statement.executeUpdate("PRAGMA wal_checkpoint(TRUNCATE)");
        }
    }

    public static void setMultiInt(PreparedStatement statement, int value, int count) {
        try {
            for (int i = 1; i <= count; i++) {
                statement.setInt(i, value);
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static boolean hasReturningKeys() {
        Backend bk = ConfigHandler.backend();
        if (bk == Backend.POSTGRES) return true;        // PG always supports RETURNING
        if (bk == Backend.SQLITE)   return ConfigHandler.SERVER_VERSION >= 20; // gated by SQLite version, mirrors legacy
        return false;                                   // MySQL: no native RETURNING — keep RETURN_GENERATED_KEYS path
    }

    public static void containerBreakCheck(String user, Material type, Object container, ItemStack[] contents, Location location) {
        if (BlockGroup.CONTAINERS.contains(type) && !BlockGroup.SHULKER_BOXES.contains(type)) {
            if (Config.getConfig(location.getWorld()).ITEM_TRANSACTIONS) {
                try {
                    if (contents == null) {
                        contents = ItemUtils.getContainerContents(type, container, location);
                    }
                    if (contents != null) {
                        List<ItemStack[]> forceList = new ArrayList<>();
                        forceList.add(ItemUtils.getContainerState(contents));
                        ConfigHandler.forceContainer.put(user.toLowerCase(Locale.ROOT) + "." + location.getBlockX() + "." + location.getBlockY() + "." + location.getBlockZ(), forceList);
                        Queue.queueContainerBreak(user, location, type, contents);
                    }
                }
                catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public static Connection getConnection(boolean onlyCheckTransacting) {
        // Previously 250ms; long consumer commit time may be due to batching (investigate removing batching for SQLite connections)
        return getConnection(false, false, onlyCheckTransacting, 1000);
    }

    public static Connection getConnection(boolean force, int waitTime) {
        return getConnection(force, false, false, waitTime);
    }

    public static Connection getConnection(boolean force, boolean startup, boolean onlyCheckTransacting, int waitTime) {
        Connection connection = null;
        try {
            if (!force && (ConfigHandler.converterRunning || ConfigHandler.purgeRunning)) {
                return connection;
            }
            if (Config.getGlobal().MYSQL) {
                try {
                    connection = ConfigHandler.hikariDataSource.getConnection();
                    if (ConfigHandler.backend() == Backend.POSTGRES) {
                        connection = net.coreprotect.database.dialect.PgConnectionProxy.wrap(connection);
                    }
                    ConfigHandler.databaseReachable = true;
                }
                catch (Exception e) {
                    ConfigHandler.databaseReachable = false;
                    Chat.sendConsoleMessage(Color.RED + "[CoreProtect] " + Phrase.build(Phrase.MYSQL_UNAVAILABLE));
                    e.printStackTrace();
                }
            }
            else {
                if (Consumer.transacting && onlyCheckTransacting) {
                    Consumer.interrupt = true;
                }

                long startTime = System.nanoTime();
                while (Consumer.isPaused && !force && (Consumer.transacting || !onlyCheckTransacting)) {
                    Thread.sleep(1);
                    long pauseTime = (System.nanoTime() - startTime) / 1000000;

                    if (pauseTime >= waitTime) {
                        return connection;
                    }
                }

                String database = "jdbc:sqlite:" + ConfigHandler.path + ConfigHandler.sqlite + "";
                connection = DriverManager.getConnection(database);

                ConfigHandler.databaseReachable = true;
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }

        return connection;
    }

    public static void closeConnection() {
        try {
            if (ConfigHandler.hikariDataSource != null) {
                ConfigHandler.hikariDataSource.close();
                ConfigHandler.hikariDataSource = null;
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void performUpdate(Statement statement, long id, int rb, int table) {
        try {
            int rolledBack = MaterialUtils.toggleRolledBack(rb, (table == 2 || table == 3 || table == 4)); // co_item, co_container, co_block
            if (table == 1 || table == 3) {
                statement.executeUpdate("UPDATE " + ConfigHandler.prefix + "container SET rolled_back='" + rolledBack + "' WHERE rowid='" + id + "'");
            }
            else if (table == 2) {
                statement.executeUpdate("UPDATE " + ConfigHandler.prefix + "item SET rolled_back='" + rolledBack + "' WHERE rowid='" + id + "'");
            }
            else {
                statement.executeUpdate("UPDATE " + ConfigHandler.prefix + "block SET rolled_back='" + rolledBack + "' WHERE rowid='" + id + "'");
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static PreparedStatement prepareStatement(Connection connection, int type, boolean keys) {
        PreparedStatement preparedStatement = null;
        try {
            String query = SQL_QUERIES.get(type);
            if (query != null) {
                query = query.replace("%sprefix%", ConfigHandler.prefix);
                preparedStatement = prepareStatement(connection, query, keys);
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }

        return preparedStatement;
    }

    private static PreparedStatement prepareStatement(Connection connection, String query, boolean keys) {
        PreparedStatement preparedStatement = null;
        try {
            if (keys) {
                if (hasReturningKeys()) {
                    preparedStatement = connection.prepareStatement(query + " RETURNING rowid");
                }
                else {
                    preparedStatement = connection.prepareStatement(query, Statement.RETURN_GENERATED_KEYS);
                }
            }
            else {
                preparedStatement = connection.prepareStatement(query);
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }

        return preparedStatement;
    }

    private static void initializeTables(String prefix, Statement statement) {
        try {
            // SQLite WAL pragma is now part of SqliteDialect.createSchema(); handled there.
            boolean lockInitialized = false;
            String query = "SELECT rowid as id FROM " + prefix + "database_lock WHERE rowid='1' LIMIT 1";
            ResultSet rs = statement.executeQuery(query);
            while (rs.next()) {
                lockInitialized = true;
            }
            rs.close();

            if (!lockInitialized) {
                int unixtimestamp = (int) (System.currentTimeMillis() / 1000L);
                statement.executeUpdate("INSERT INTO " + prefix + "database_lock (rowid, status, time) VALUES ('1', '0', '" + unixtimestamp + "')");
                Process.lastLockUpdate = 0;
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static final List<String> DATABASE_TABLES = Arrays.asList("art_map", "block", "chat", "command", "container", "item", "database_lock", "entity", "entity_map", "material_map", "blockdata_map", "session", "sign", "skull", "user", "username_log", "version", "world");

    public static void createDatabaseTables(String prefix, boolean forcePrefix, Connection forceConnection, boolean mySQL, boolean purge) {
        ConfigHandler.databaseTables.clear();
        ConfigHandler.databaseTables.addAll(DATABASE_TABLES);

        Dialect dialect = ConfigHandler.dialect();
        if (dialect != null) {
            createSchemaViaDialect(dialect, prefix, forcePrefix, forceConnection, purge);
            return;
        }
        // Legacy paths (kept for older callers / patch scripts before init).
        if (mySQL) {
            createMySQLTablesLegacy(prefix, forceConnection, purge);
        }
        else {
            createSQLiteTablesLegacy(prefix, forcePrefix, forceConnection, purge);
        }
    }

    private static void createSchemaViaDialect(Dialect dialect, String prefix, boolean forcePrefix, Connection forceConnection, boolean purge) {
        boolean success = false;
        Connection connection = null;
        try {
            connection = (forceConnection != null
                    ? forceConnection
                    : (dialect.backend().isRemote()
                            ? Database.getConnection(true, true, true, 0)
                            : Database.getConnection(true, 0)));
            if (connection != null) {
                Statement statement = connection.createStatement();
                dialect.createSchema(statement, prefix);
                if (!purge && forceConnection == null) {
                    initializeTables(prefix, statement);
                }
                statement.close();
                success = true;
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
        finally {
            if (forceConnection == null && connection != null) {
                try { connection.close(); } catch (SQLException ignored) {}
            }
        }
        if (!success && forceConnection == null && dialect.backend().isRemote()) {
            // Remote DB unreachable — fall back to SQLite as the legacy code did for MySQL.
            Config.getGlobal().MYSQL = false;
        }
    }

    // ----- Legacy schema creation paths kept verbatim for callers that bypass the dialect cache -----

    private static void createMySQLTablesLegacy(String prefix, Connection forceConnection, boolean purge) {
        boolean success = false;
        try (Connection connection = (forceConnection != null ? forceConnection : Database.getConnection(true, true, true, 0))) {
            if (connection != null) {
                Statement statement = connection.createStatement();
                createMySQLTableStructures(prefix, statement);
                createMySQLIndexes(prefix, statement, purge);
                if (!purge && forceConnection == null) {
                    initializeTables(prefix, statement);
                }
                statement.close();
                success = true;
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
        if (!success && forceConnection == null) {
            Config.getGlobal().MYSQL = false;
        }
    }

    private static void createMySQLTableStructures(String prefix, Statement statement) throws SQLException {
        new net.coreprotect.database.dialect.MysqlDialect().createSchema(statement, prefix);
    }

    private static void createMySQLIndexes(String prefix, Statement statement, boolean purge) {
        // Index creation now happens inside MysqlDialect.createSchema; kept as a no-op for legacy callers.
    }

    private static void createSQLiteTablesLegacy(String prefix, boolean forcePrefix, Connection forceConnection, boolean purge) {
        try (Connection connection = (forceConnection != null ? forceConnection : Database.getConnection(true, 0))) {
            Statement statement = connection.createStatement();
            new net.coreprotect.database.dialect.SqliteDialect().createSchema(statement, prefix);
            if (!purge && forceConnection == null) {
                initializeTables(prefix, statement);
            }
            statement.close();
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }
}
