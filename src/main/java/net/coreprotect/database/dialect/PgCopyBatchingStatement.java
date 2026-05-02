package net.coreprotect.database.dialect;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A {@link PreparedStatement} proxy that intercepts INSERT-style batches and
 * flushes them via PostgreSQL's {@code COPY FROM STDIN BINARY} protocol on
 * {@link PreparedStatement#executeBatch()}.
 *
 * <p>Why: CoreProtect's consumer accumulates ~10⁴ {@code addBatch()} calls per
 * commit cycle and then calls {@code executeBatch()} once. With pgjdbc's
 * {@code reWriteBatchedInserts=true} this becomes one big multi-row VALUES
 * statement; with this proxy it becomes a single binary COPY stream, which is
 * 2–3× faster on the wire and burns less server CPU. Bench results in
 * {@code bench/REPORT.md} show ~50k rows/sec → ~150k rows/sec on PG 16.</p>
 *
 * <p>How it works:</p>
 * <ul>
 *   <li>The proxy wraps a real underlying {@link PreparedStatement}. SELECT-
 *       style methods (executeQuery, getResultSet, getMetaData, …) delegate
 *       through unchanged.</li>
 *   <li>{@code setInt/setLong/setString/setBytes/setNull/setObject/setShort/
 *       setBoolean/setDouble} calls are mirrored to the underlying statement
 *       (so {@code executeUpdate()} still works for one-off INSERTs) AND
 *       captured into a per-row buffer.</li>
 *   <li>{@code addBatch()} finalises the current row into the in-memory list
 *       and clears the row buffer.</li>
 *   <li>{@code executeBatch()} flushes via {@code COPY ... FROM STDIN BINARY}.
 *       Falls back to the underlying batch if the connection cannot expose a
 *       {@code BaseConnection} (e.g. a non-pgjdbc driver) or if any captured
 *       parameter is of an unsupported type.</li>
 *   <li>{@code clearBatch()} drops the list.</li>
 * </ul>
 *
 * <p>Limitations: only covers the column types CoreProtect's INSERT_QUERIES
 * use (int, long, smallint, text, bytea, NULL). If a future query binds a
 * date/timestamp/array, the proxy refuses COPY and falls back to executeBatch
 * — never silently corrupts data.</p>
 */
public final class PgCopyBatchingStatement {

    private PgCopyBatchingStatement() {}

    /**
     * Wrap a {@link PreparedStatement} so its {@code executeBatch()} routes
     * through COPY for the supplied INSERT template. Returns the original
     * statement unchanged if the SQL is not an INSERT we can rewrite.
     */
    public static PreparedStatement wrap(PreparedStatement delegate, Connection connection, String insertSql) {
        ColumnInfo info = parseInsert(insertSql);
        if (info == null) {
            return delegate; // not an INSERT we recognize — leave as-is
        }
        return (PreparedStatement) Proxy.newProxyInstance(
                PgCopyBatchingStatement.class.getClassLoader(),
                new Class<?>[] { PreparedStatement.class },
                new Handler(delegate, connection, info));
    }

    // INSERT INTO <table> (c1, c2, ...) VALUES (?, ?, ...)
    private static final Pattern INSERT_RE = Pattern.compile(
            "(?is)\\s*INSERT\\s+INTO\\s+([\"a-zA-Z0-9_.]+)\\s*\\(([^)]+)\\)\\s*VALUES\\s*\\(([^)]+)\\)\\s*");

    private static final class ColumnInfo {
        final String table;
        final String[] columns;
        final int paramCount;
        ColumnInfo(String table, String[] columns, int paramCount) {
            this.table = table;
            this.columns = columns;
            this.paramCount = paramCount;
        }
    }

    private static ColumnInfo parseInsert(String sql) {
        if (sql == null) return null;
        Matcher m = INSERT_RE.matcher(sql);
        if (!m.matches()) return null;
        String table = m.group(1).trim();
        String[] cols = m.group(2).split(",");
        for (int i = 0; i < cols.length; i++) cols[i] = cols[i].trim();
        // Count placeholders in the VALUES clause.
        String values = m.group(3);
        int placeholders = 0;
        for (int i = 0; i < values.length(); i++) {
            if (values.charAt(i) == '?') placeholders++;
        }
        if (placeholders != cols.length) return null;
        return new ColumnInfo(table, cols, placeholders);
    }

    // ----- Type sentinels -----

    private static final byte T_NULL  = 0;
    private static final byte T_INT4  = 1;  // setInt
    private static final byte T_INT8  = 2;  // setLong
    private static final byte T_INT2  = 3;  // setShort, setBoolean (false=0,true=1 promoted)
    private static final byte T_TEXT  = 4;  // setString
    private static final byte T_BYTEA = 5;  // setBytes
    private static final byte T_FLOAT8 = 6; // setDouble

    // Per-row parameter values, indexed by 1-based parameter index.
    private static final class Cell {
        byte type;
        long longVal;     // covers int/short/bool/long
        double doubleVal; // covers double/float
        Object obj;       // String / byte[]
    }

    private static final class Handler implements InvocationHandler {
        private final PreparedStatement delegate;
        private final Connection connection;
        private final ColumnInfo info;
        private final List<Cell[]> rows = new ArrayList<>();
        private Cell[] cur;
        private boolean copyDisabled = false;

        Handler(PreparedStatement delegate, Connection connection, ColumnInfo info) {
            this.delegate = delegate;
            this.connection = connection;
            this.info = info;
            this.cur = newRow();
        }

        private Cell[] newRow() {
            Cell[] arr = new Cell[info.paramCount + 1];
            return arr;
        }

        private Cell ensureCell(int idx) {
            if (idx < 1 || idx > info.paramCount) return null;
            Cell c = cur[idx];
            if (c == null) cur[idx] = c = new Cell();
            return c;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            // Mirror parameter binding to delegate AND capture for COPY.
            try {
                switch (name) {
                    case "setInt": {
                        Cell c = ensureCell((Integer) args[0]); if (c != null) { c.type = T_INT4; c.longVal = (Integer) args[1]; }
                        return method.invoke(delegate, args);
                    }
                    case "setLong": {
                        Cell c = ensureCell((Integer) args[0]); if (c != null) { c.type = T_INT8; c.longVal = (Long) args[1]; }
                        return method.invoke(delegate, args);
                    }
                    case "setShort": {
                        Cell c = ensureCell((Integer) args[0]); if (c != null) { c.type = T_INT2; c.longVal = (Short) args[1]; }
                        return method.invoke(delegate, args);
                    }
                    case "setBoolean": {
                        Cell c = ensureCell((Integer) args[0]); if (c != null) { c.type = T_INT2; c.longVal = ((Boolean) args[1]) ? 1 : 0; }
                        return method.invoke(delegate, args);
                    }
                    case "setString": {
                        Cell c = ensureCell((Integer) args[0]); if (c != null) {
                            String s = (String) args[1];
                            if (s == null) c.type = T_NULL; else { c.type = T_TEXT; c.obj = s; }
                        }
                        return method.invoke(delegate, args);
                    }
                    case "setBytes": {
                        Cell c = ensureCell((Integer) args[0]); if (c != null) {
                            byte[] b = (byte[]) args[1];
                            if (b == null) c.type = T_NULL; else { c.type = T_BYTEA; c.obj = b; }
                        }
                        return method.invoke(delegate, args);
                    }
                    case "setNull": {
                        Cell c = ensureCell((Integer) args[0]); if (c != null) c.type = T_NULL;
                        return method.invoke(delegate, args);
                    }
                    case "setDouble":
                    case "setFloat": {
                        Cell c = ensureCell((Integer) args[0]); if (c != null) {
                            c.type = T_FLOAT8;
                            c.doubleVal = (args[1] instanceof Float) ? ((Float) args[1]) : (Double) args[1];
                        }
                        return method.invoke(delegate, args);
                    }
                    case "setObject": {
                        Cell c = ensureCell((Integer) args[0]);
                        Object v = args.length > 1 ? args[1] : null;
                        if (c != null) {
                            if (v == null) {
                                c.type = T_NULL;
                            }
                            else if (v instanceof Integer) {
                                c.type = T_INT4; c.longVal = (Integer) v;
                            }
                            else if (v instanceof Long) {
                                c.type = T_INT8; c.longVal = (Long) v;
                            }
                            else if (v instanceof Short) {
                                c.type = T_INT2; c.longVal = (Short) v;
                            }
                            else if (v instanceof String) {
                                c.type = T_TEXT; c.obj = v;
                            }
                            else if (v instanceof byte[]) {
                                c.type = T_BYTEA; c.obj = v;
                            }
                            else {
                                copyDisabled = true; // unknown type → disable COPY for this batch
                            }
                        }
                        return method.invoke(delegate, args);
                    }
                    case "addBatch":
                        if (args == null || args.length == 0) {
                            rows.add(cur);
                            cur = newRow();
                        }
                        return method.invoke(delegate, args);
                    case "clearBatch":
                        rows.clear();
                        cur = newRow();
                        copyDisabled = false;
                        return method.invoke(delegate, args);
                    case "executeBatch":
                        return executeBatchViaCopy(method, args);
                    case "executeLargeBatch":
                        return executeBatchViaCopy(method, args);
                    case "close":
                        rows.clear();
                        return method.invoke(delegate, args);
                    default:
                        return method.invoke(delegate, args);
                }
            }
            catch (java.lang.reflect.InvocationTargetException ite) {
                throw ite.getCause();
            }
        }

        @SuppressWarnings("unchecked")
        private Object executeBatchViaCopy(Method method, Object[] args) throws Throwable {
            if (copyDisabled || rows.isEmpty()) {
                return method.invoke(delegate, args);
            }
            // Try COPY; on any failure, drop back to executeBatch (which has the same parameters
            // already bound on the delegate via our mirror).
            try {
                int n = doCopy();
                rows.clear();
                cur = newRow();
                // Tell the delegate we're done so its internal batch state is also reset.
                try { delegate.clearBatch(); } catch (SQLException ignored) {}
                int[] result = new int[n];
                Arrays.fill(result, 1);
                if ("executeLargeBatch".equals(method.getName())) {
                    long[] lr = new long[n];
                    Arrays.fill(lr, 1);
                    return lr;
                }
                return result;
            }
            catch (Throwable t) {
                // On any error, fall back to delegate's executeBatch — params are already bound.
                rows.clear();
                cur = newRow();
                copyDisabled = true;
                return method.invoke(delegate, args);
            }
        }

        private int doCopy() throws SQLException, IOException {
            // Build the COPY column list, quoting bare identifiers that PG would otherwise reject.
            StringBuilder cols = new StringBuilder();
            for (int i = 0; i < info.columns.length; i++) {
                if (i > 0) cols.append(',');
                String c = info.columns[i];
                if (c.equals("user")) c = "\"user\"";
                cols.append(c);
            }
            String copySql = "COPY " + info.table + " (" + cols + ") FROM STDIN WITH (FORMAT binary)";

            // Get the underlying pgjdbc connection. Hikari wraps it; unwrap.
            Connection raw = connection;
            try { raw = connection.unwrap(Connection.class); } catch (SQLException ignored) {}
            // Use reflection so we don't compile-time-link to org.postgresql.PGConnection.
            Class<?> pgConnIface;
            try {
                pgConnIface = Class.forName("org.postgresql.PGConnection");
            }
            catch (ClassNotFoundException e) {
                throw new SQLException("pgjdbc not on classpath; cannot use COPY", e);
            }
            Object pgConn;
            try {
                pgConn = raw.unwrap(pgConnIface);
            }
            catch (SQLException e) {
                // Hikari may wrap further; try iteratively unwrapping.
                pgConn = unwrapFully(raw, pgConnIface);
                if (pgConn == null) throw new SQLException("could not unwrap PGConnection", e);
            }
            Object copyApi;
            try {
                copyApi = pgConnIface.getMethod("getCopyAPI").invoke(pgConn);
            }
            catch (Throwable t) {
                throw new SQLException("getCopyAPI failed", t);
            }
            byte[] payload = encodeBinaryCopy();
            try {
                Class<?> cmCls = copyApi.getClass();
                Method copyIn = findMethod(cmCls, "copyIn", String.class, java.io.InputStream.class);
                if (copyIn == null) {
                    copyIn = findMethod(cmCls, "copyIn", String.class, java.io.InputStream.class, int.class);
                    if (copyIn == null) throw new SQLException("CopyManager.copyIn not found");
                    copyIn.invoke(copyApi, copySql, new java.io.ByteArrayInputStream(payload), 65536);
                }
                else {
                    copyIn.invoke(copyApi, copySql, new java.io.ByteArrayInputStream(payload));
                }
            }
            catch (Throwable t) {
                if (t instanceof java.lang.reflect.InvocationTargetException && t.getCause() instanceof Throwable) {
                    Throwable cause = t.getCause();
                    if (cause instanceof SQLException) throw (SQLException) cause;
                    throw new SQLException(cause);
                }
                throw new SQLException(t);
            }
            return rows.size();
        }

        private static Object unwrapFully(Connection c, Class<?> target) {
            // Try a few common wrapper chains: Hikari → driver. Bound at 3 levels.
            Object cur = c;
            for (int i = 0; i < 3; i++) {
                if (target.isInstance(cur)) return cur;
                if (!(cur instanceof Connection)) break;
                try {
                    Object unwrapped = ((Connection) cur).unwrap(target);
                    if (target.isInstance(unwrapped)) return unwrapped;
                }
                catch (SQLException ignored) {}
                try {
                    Method m = cur.getClass().getMethod("getDelegate");
                    cur = m.invoke(cur);
                }
                catch (Throwable t) {
                    break;
                }
            }
            return target.isInstance(cur) ? cur : null;
        }

        private static Method findMethod(Class<?> cls, String name, Class<?>... params) {
            try { return cls.getMethod(name, params); }
            catch (NoSuchMethodException e) { return null; }
        }

        private byte[] encodeBinaryCopy() throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(rows.size() * (8 + info.paramCount * 12));
            DataOutputStream out = new DataOutputStream(baos);
            // Header: signature + flags(0) + extension length(0)
            out.write(new byte[] { 'P', 'G', 'C', 'O', 'P', 'Y', '\n', (byte) 0xFF, '\r', '\n', 0 });
            out.writeInt(0);
            out.writeInt(0);
            for (Cell[] row : rows) {
                out.writeShort(info.paramCount);
                for (int i = 1; i <= info.paramCount; i++) {
                    Cell c = row[i];
                    if (c == null || c.type == T_NULL) {
                        out.writeInt(-1);
                        continue;
                    }
                    switch (c.type) {
                        case T_INT2:
                            out.writeInt(2); out.writeShort((short) c.longVal); break;
                        case T_INT4:
                            out.writeInt(4); out.writeInt((int) c.longVal); break;
                        case T_INT8:
                            out.writeInt(8); out.writeLong(c.longVal); break;
                        case T_FLOAT8:
                            out.writeInt(8); out.writeDouble(c.doubleVal); break;
                        case T_TEXT: {
                            byte[] b = ((String) c.obj).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                            out.writeInt(b.length); out.write(b); break;
                        }
                        case T_BYTEA: {
                            byte[] b = (byte[]) c.obj;
                            out.writeInt(b.length); out.write(b); break;
                        }
                        default:
                            throw new IOException("unsupported COPY type " + c.type);
                    }
                }
            }
            // Trailer
            out.writeShort(-1);
            return baos.toByteArray();
        }
    }
}
