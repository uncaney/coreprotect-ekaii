package net.coreprotect.database.dialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import net.coreprotect.consumer.Consumer;
import net.coreprotect.database.Backend;

public final class MysqlDialect implements Dialect {

    @Override
    public Backend backend() { return Backend.MYSQL; }

    @Override
    public String driverClassName() { return "com.mysql.cj.jdbc.Driver"; }

    @Override
    public String fallbackDriverClassName() { return "com.mysql.jdbc.Driver"; }

    @Override
    public String jdbcUrl(String host, int port, String database) {
        return "jdbc:mysql://" + host + ":" + port + "/" + database;
    }

    @Override
    public void beginTransaction(Statement statement) {
        try {
            statement.executeUpdate("START TRANSACTION");
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void commitTransaction(Statement statement) throws Exception {
        try {
            statement.executeUpdate("COMMIT");
        }
        catch (Exception e) {
            e.printStackTrace();
        }
        finally {
            Consumer.transacting = false;
            Consumer.interrupt = false;
        }
    }

    @Override
    public void performCheckpoint(Statement statement) throws SQLException {
        // no-op on MySQL
    }

    @Override
    public void createSchema(Statement statement, String prefix) throws SQLException {
        String idx;
        idx = ", INDEX(id)";
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "art_map(rowid int NOT NULL AUTO_INCREMENT,PRIMARY KEY(rowid),id int,art varchar(255)" + idx + ") ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4");
        idx = ", INDEX(wid,x,z,time), INDEX(user,time), INDEX(type,time)";
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "block(rowid bigint NOT NULL AUTO_INCREMENT,PRIMARY KEY(rowid), time int, user int, wid int, x int, y int, z int, type int, data int, meta mediumblob, blockdata blob, action tinyint, rolled_back tinyint" + idx + ") ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4");
        idx = ", INDEX(time), INDEX(user,time), INDEX(wid,x,z,time)";
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "chat(rowid int NOT NULL AUTO_INCREMENT,PRIMARY KEY(rowid),time int, user int, wid int, x int, y int (3), z int, message varchar(16000)" + idx + ") ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4");
        idx = ", INDEX(time), INDEX(user,time), INDEX(wid,x,z,time)";
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "command(rowid int NOT NULL AUTO_INCREMENT,PRIMARY KEY(rowid),time int, user int, wid int, x int, y int (3), z int, message varchar(16000)" + idx + ") ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4");
        idx = ", INDEX(wid,x,z,time), INDEX(user,time), INDEX(type,time)";
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "container(rowid int NOT NULL AUTO_INCREMENT,PRIMARY KEY(rowid), time int, user int, wid int, x int, y int, z int, type int, data int, amount int, metadata blob, action tinyint, rolled_back tinyint" + idx + ") ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4");
        idx = ", INDEX(wid,x,z,time), INDEX(user,time), INDEX(type,time)";
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "item(rowid int NOT NULL AUTO_INCREMENT,PRIMARY KEY(rowid), time int, user int, wid int, x int, y int, z int, type int, data blob, amount int, action tinyint, rolled_back tinyint" + idx + ") ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4");
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "database_lock(rowid int NOT NULL AUTO_INCREMENT,PRIMARY KEY(rowid),status tinyint,time int) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4");
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "entity(rowid int NOT NULL AUTO_INCREMENT,PRIMARY KEY(rowid), time int, data blob) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4");
        idx = ", INDEX(id)";
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "entity_map(rowid int NOT NULL AUTO_INCREMENT,PRIMARY KEY(rowid),id int,entity varchar(255)" + idx + ") ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4");
        idx = ", INDEX(id)";
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "material_map(rowid int NOT NULL AUTO_INCREMENT,PRIMARY KEY(rowid),id int,material varchar(255)" + idx + ") ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4");
        idx = ", INDEX(id)";
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "blockdata_map(rowid int NOT NULL AUTO_INCREMENT,PRIMARY KEY(rowid),id int,data varchar(255)" + idx + ") ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4");
        idx = ", INDEX(wid,x,z,time), INDEX(action,time), INDEX(user,time), INDEX(time)";
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "session(rowid int NOT NULL AUTO_INCREMENT,PRIMARY KEY(rowid),time int, user int, wid int, x int, y int (3), z int, action tinyint" + idx + ") ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4");
        idx = ", INDEX(wid,x,z,time), INDEX(user,time), INDEX(time)";
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "sign(rowid int NOT NULL AUTO_INCREMENT,PRIMARY KEY(rowid),time int, user int, wid int, x int, y int, z int, action tinyint, color int, color_secondary int, data tinyint, waxed tinyint, face tinyint, line_1 varchar(100), line_2 varchar(100), line_3 varchar(100), line_4 varchar(100), line_5 varchar(100), line_6 varchar(100), line_7 varchar(100), line_8 varchar(100)" + idx + ") ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4");
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "skull(rowid int NOT NULL AUTO_INCREMENT,PRIMARY KEY(rowid), time int, owner varchar(255), skin varchar(255)) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4");
        idx = ", INDEX(user), INDEX(uuid)";
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "user(rowid int NOT NULL AUTO_INCREMENT,PRIMARY KEY(rowid),time int,user varchar(100),uuid varchar(64)" + idx + ") ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4");
        idx = ", INDEX(uuid,user)";
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "username_log(rowid int NOT NULL AUTO_INCREMENT,PRIMARY KEY(rowid),time int,uuid varchar(64),user varchar(100)" + idx + ") ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4");
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "version(rowid int NOT NULL AUTO_INCREMENT,PRIMARY KEY(rowid),time int,version varchar(16)) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4");
        idx = ", INDEX(id)";
        statement.executeUpdate("CREATE TABLE IF NOT EXISTS " + prefix + "world(rowid int NOT NULL AUTO_INCREMENT,PRIMARY KEY(rowid),id int,world varchar(255)" + idx + ") ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4");

        ensureMySQLIndex(statement, prefix + "block",     "wid", "x", "z", "time");
        ensureMySQLIndex(statement, prefix + "block",     "user", "time");
        ensureMySQLIndex(statement, prefix + "block",     "type", "time");
        ensureMySQLIndex(statement, prefix + "block",     "time");
        ensureMySQLIndex(statement, prefix + "container", "wid", "x", "z", "time");
        ensureMySQLIndex(statement, prefix + "container", "user", "time");
        ensureMySQLIndex(statement, prefix + "container", "type", "time");
        ensureMySQLIndex(statement, prefix + "container", "time");
        ensureMySQLIndex(statement, prefix + "item",      "wid", "x", "z", "time");
        ensureMySQLIndex(statement, prefix + "item",      "user", "time");
        ensureMySQLIndex(statement, prefix + "item",      "type", "time");
        ensureMySQLIndex(statement, prefix + "item",      "time");
    }

    @Override
    public int purgeOldRows(Connection connection, String prefixedTable, long beforeUnixSeconds, int chunkLimit) throws SQLException {
        String sql = "DELETE FROM " + prefixedTable + " WHERE time < ? ORDER BY time ASC LIMIT ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setLong(1, beforeUnixSeconds);
            ps.setInt(2, chunkLimit);
            return ps.executeUpdate();
        }
    }

    @Override
    public boolean supportsReturningClause() { return false; }

    @Override
    public boolean supportsOnConflict() { return false; }

    private static void ensureMySQLIndex(Statement statement, String tableName, String... columns) throws SQLException {
        if (hasMySQLIndex(statement, tableName, columns)) return;
        String indexName = createMySQLIndexName(tableName, columns);
        String indexColumns = String.join(",", columns);
        try {
            statement.executeUpdate("CREATE INDEX " + indexName + " ON " + tableName + "(" + indexColumns + ")");
        }
        catch (SQLException ignored) {
            // index may already exist under a different name; let it slide.
        }
    }

    private static boolean hasMySQLIndex(Statement statement, String tableName, String... columns) {
        Map<String, TreeMap<Integer, String>> indexData = new HashMap<>();
        try (ResultSet rs = statement.executeQuery("SHOW INDEX FROM " + tableName)) {
            while (rs.next()) {
                String keyName = rs.getString("Key_name");
                int sequence = rs.getInt("Seq_in_index");
                String columnName = rs.getString("Column_name");
                if (keyName == null || columnName == null) continue;
                indexData.computeIfAbsent(keyName, k -> new TreeMap<>()).put(sequence, columnName.toLowerCase(Locale.ROOT));
            }
        }
        catch (Exception e) {
            return false;
        }
        List<String> expected = new ArrayList<>(columns.length);
        for (String c : columns) expected.add(c.toLowerCase(Locale.ROOT));
        for (TreeMap<Integer, String> idxCols : indexData.values()) {
            if (idxCols.size() < expected.size()) continue;
            boolean ok = true;
            int p = 0;
            for (String c : idxCols.values()) {
                if (!c.equals(expected.get(p))) { ok = false; break; }
                p++;
                if (p == expected.size()) break;
            }
            if (ok && p == expected.size()) return true;
        }
        return false;
    }

    private static String createMySQLIndexName(String tableName, String... columns) {
        String n = tableName.replaceAll("[^A-Za-z0-9_]", "_");
        String c = String.join("_", columns);
        String candidate = n + "_" + c + "_idx";
        if (candidate.length() <= 64) return candidate;
        String hash = Integer.toHexString(candidate.hashCode());
        int max = 64 - (hash.length() + 1);
        if (max < 1) max = 1;
        return candidate.substring(0, max) + "_" + hash;
    }
}
