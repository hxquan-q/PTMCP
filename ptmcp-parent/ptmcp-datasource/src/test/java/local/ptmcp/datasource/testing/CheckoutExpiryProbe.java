package local.ptmcp.datasource.testing;

import local.ptmcp.common.execution.ApprovedSqlPlan;
import local.ptmcp.common.resource.ResourceId;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

/** Advances approval time after a real checkout and counts actual submissions of the user SQL. */
public final class CheckoutExpiryProbe extends Clock {
    private static final Instant NOW = Instant.parse("2026-10-27T00:00:00Z");
    private final AtomicInteger executions = new AtomicInteger();
    private final String sql = "SELECT 1 AS expiry_probe";
    private Instant now = NOW;

    public ApprovedSqlPlan plan() throws Exception {
        return new Plan(
                new ResourceId(1),
                1,
                sql,
                MessageDigest.getInstance("SHA-256").digest(sql.getBytes(StandardCharsets.UTF_8)),
                1,
                NOW,
                NOW.plusSeconds(10));
    }

    public int executions() {
        return executions.get();
    }

    public DataSource wrap(DataSource delegate, boolean closeDelegate) {
        return (DataSource)
                Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {DataSource.class, AutoCloseable.class},
                        (proxy, method, args) -> {
                            if (method.getName().equals("close") && !closeDelegate) {
                                return null;
                            }
                            Object result = invoke(delegate, method, args);
                            if (result instanceof Connection connection
                                    && method.getName().equals("getConnection")) {
                                now = NOW.plusSeconds(30);
                                return connection(connection);
                            }
                            return result;
                        });
    }

    private Connection connection(Connection delegate) {
        return (Connection)
                Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {Connection.class},
                        (proxy, method, args) -> {
                            Object result = invoke(delegate, method, args);
                            if (result instanceof Statement statement) {
                                boolean prepared = statement instanceof PreparedStatement;
                                boolean userPrepared =
                                        prepared && args != null && sql.equals(args[0]);
                                return Proxy.newProxyInstance(
                                        getClass().getClassLoader(),
                                        new Class<?>[] {
                                            prepared ? PreparedStatement.class : Statement.class
                                        },
                                        (statementProxy, statementMethod, statementArgs) -> {
                                            if (statementMethod.getName().startsWith("execute")
                                                    && (userPrepared
                                                            || (statementArgs != null
                                                                    && statementArgs.length > 0
                                                                    && sql.equals(
                                                                            statementArgs[0])))) {
                                                executions.incrementAndGet();
                                            }
                                            return invoke(
                                                    statement, statementMethod, statementArgs);
                                        });
                            }
                            return result;
                        });
    }

    private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.fixed(now, zone);
    }

    @Override
    public Instant instant() {
        return now;
    }

    private record Plan(
            ResourceId datasourceId,
            long configVersion,
            String finalSql,
            byte[] sqlHash,
            int effectiveLimit,
            Instant approvedAt,
            Instant expiresAt)
            implements ApprovedSqlPlan {}
}
