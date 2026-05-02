package net.coreprotect.database.dialect;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;

/**
 * Wraps a JDBC {@link Connection} so that every SQL string passing through it
 * is translated by {@link SqlTranslator#toPostgres(String)} before reaching
 * the pgjdbc driver.
 *
 * <p>The proxy chains: {@code Connection} → {@code Statement} / {@code PreparedStatement}
 * → SQL strings. We cover the methods CoreProtect actually calls:
 * {@code prepareStatement}, {@code prepareCall}, {@code createStatement} on Connection;
 * {@code executeQuery}, {@code executeUpdate}, {@code execute}, {@code addBatch} on Statement.
 * Other methods pass through unchanged.</p>
 *
 * <p>Performance: each translated SQL goes through ~3 short regex passes.
 * For the SQL_QUERIES INSERT templates (the hot path) HikariCP caches
 * prepared statements, so translation runs once per statement, not per row.</p>
 */
public final class PgConnectionProxy {

    private PgConnectionProxy() {}

    public static Connection wrap(Connection delegate) {
        if (delegate == null) return null;
        return (Connection) Proxy.newProxyInstance(
                PgConnectionProxy.class.getClassLoader(),
                new Class<?>[] { Connection.class },
                new ConnectionHandler(delegate));
    }

    private static final class ConnectionHandler implements InvocationHandler {
        private final Connection delegate;
        ConnectionHandler(Connection delegate) { this.delegate = delegate; }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (("prepareStatement".equals(name) || "prepareCall".equals(name) || "nativeSQL".equals(name))
                    && args != null && args.length > 0 && args[0] instanceof String) {
                args[0] = SqlTranslator.toPostgres((String) args[0]);
                Object result = method.invoke(delegate, args);
                if (result instanceof PreparedStatement) {
                    return wrapPrepared((PreparedStatement) result);
                }
                return result;
            }
            if ("createStatement".equals(name)) {
                Object result = method.invoke(delegate, args);
                if (result instanceof Statement) {
                    return wrapStatement((Statement) result);
                }
                return result;
            }
            return method.invoke(delegate, args);
        }
    }

    private static Statement wrapStatement(Statement st) {
        return (Statement) Proxy.newProxyInstance(
                PgConnectionProxy.class.getClassLoader(),
                new Class<?>[] { Statement.class },
                new StatementHandler(st));
    }

    private static PreparedStatement wrapPrepared(PreparedStatement ps) {
        // PreparedStatement SQL is already translated when it was prepared; no further work.
        return ps;
    }

    private static final class StatementHandler implements InvocationHandler {
        private final Statement delegate;
        StatementHandler(Statement delegate) { this.delegate = delegate; }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (args != null && args.length > 0 && args[0] instanceof String
                    && ("executeQuery".equals(name) || "executeUpdate".equals(name)
                        || "execute".equals(name) || "executeLargeUpdate".equals(name)
                        || "addBatch".equals(name))) {
                args[0] = SqlTranslator.toPostgres((String) args[0]);
            }
            return method.invoke(delegate, args);
        }
    }
}
