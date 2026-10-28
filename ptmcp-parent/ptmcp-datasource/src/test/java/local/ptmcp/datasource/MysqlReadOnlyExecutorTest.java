package local.ptmcp.datasource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.common.resource.DatabaseEngine;
import local.ptmcp.datasource.testing.CheckoutExpiryProbe;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.mysql.MySQLContainer;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import javax.sql.DataSource;

/** Real MySQL boundary for the read-only executor protocol. */
class MysqlReadOnlyExecutorTest {
    private static final int PROBE_ROWS = 150;
    private static final List<ExecutionTrace> TRACES = new CopyOnWriteArrayList<>();
    private static MySQLContainer mysql;
    private static String password;
    private static DataSourcePoolRegistry registry;
    private static MysqlReadOnlyExecutor executor;
    private static PoolHandle handle;

    @BeforeAll
    static void startContainerAndSeedProbe() throws Exception {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        password = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        mysql =
                new MySQLContainer(
                                System.getProperty(
                                        "ptmcp.test.mysqlImage",
                                        "mysql@sha256:76f0358884cb92d9a086d24e5671c38048d3fef40f2588703d73eb55392d6b69"))
                        .withDatabaseName("datasource_test")
                        .withUsername("datasource_test")
                        .withPassword(password)
                        .withEnv("TZ", "UTC")
                        .withCommand(
                                "--default-time-zone=+00:00",
                                "--character-set-server=utf8mb4",
                                "--collation-server=utf8mb4_0900_bin")
                        .withUrlParam("connectionTimeZone", "UTC")
                        .withUrlParam("forceConnectionTimeZoneToSession", "true")
                        .withUrlParam("allowPublicKeyRetrieval", "true")
                        .withUrlParam("useSSL", "false");
        mysql.start();
        try (Connection connection =
                        DriverManager.getConnection(
                                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE probe (id BIGINT PRIMARY KEY, name VARCHAR(32))");
            for (int id = 1; id <= PROBE_ROWS; id++) {
                statement.execute(
                        "INSERT INTO probe (id, name) VALUES (" + id + ", 'row-" + id + "')");
            }
        } catch (Exception | AssertionError failure) {
            mysql.stop();
            throw failure;
        }
    }

    @AfterAll
    static void stopContainer() {
        if (mysql != null) {
            mysql.stop();
        }
    }

    @BeforeEach
    void createExecutor() {
        TRACES.clear();
        registry = new DataSourcePoolRegistry();
        executor = new MysqlReadOnlyExecutor(registry, ExecutorSettings.defaults(), TRACES::add);
        handle = registry.activate(key(1, 1), password.toCharArray(), PoolLimits.of(1));
    }

    @AfterEach
    void closeRegistry() {
        if (registry != null) {
            registry.close();
        }
    }

    @Test
    void readOnlyTransactionRejectsUpdateAndReportsTheLifecycle() throws Exception {
        DomainException failure =
                assertThrows(
                        DomainException.class,
                        () ->
                                executor.execute(
                                        1,
                                        request(
                                                "UPDATE probe SET name = 'changed' WHERE id = 1",
                                                10),
                                        new ReadOnlyCancellation()));

        assertEquals(ErrorCode.SQL_NOT_READONLY, failure.errorCode());
        try (Connection connection =
                        DriverManager.getConnection(
                                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
                Statement statement = connection.createStatement();
                ResultSet resultSet =
                        statement.executeQuery("SELECT name FROM probe WHERE id = 1")) {
            assertTrue(resultSet.next());
            assertEquals("row-1", resultSet.getString(1));
        }
        assertEquals(1, TRACES.size());
        ExecutionTrace trace = TRACES.get(0);
        assertEquals(
                List.of(
                        ExecutionState.ACQUIRED,
                        ExecutionState.CONFIGURED,
                        ExecutionState.EXECUTING,
                        ExecutionState.DRAINING,
                        ExecutionState.CLEANING,
                        ExecutionState.RETURNED),
                trace.states());
        assertEquals(
                List.of(
                        ExecutionStage.CHECKOUT,
                        ExecutionStage.CONFIGURE,
                        ExecutionStage.EXECUTE,
                        ExecutionStage.DRAIN,
                        ExecutionStage.CLEAN,
                        ExecutionStage.RETURN),
                trace.stages());
        assertEquals(ExecutionState.RETURNED, trace.finalState());
        assertEquals(handle.generation(), trace.poolGeneration());
        assertTrue(trace.entries().stream().allMatch(entry -> entry.durationMillis() >= 0));
        assertFalse(trace.toString().contains("probe"));
    }

    @Test
    void executorReplacesPollutedSessionWithUtcReadOnlyBaseline() throws Exception {
        try (Connection connection = handle.dataSource().getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("SET time_zone = '+09:00'");
            statement.execute("SET SESSION max_execution_time = 99999");
            statement.execute("SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ");
        }

        ReadOnlyResult result =
                executor.execute(
                        1,
                        request(
                                "SELECT @@session.time_zone AS tz, @@session.max_execution_time AS budget, "
                                        + "@@session.transaction_read_only AS ro",
                                1),
                        new ReadOnlyCancellation());

        assertEquals(1, result.rows().size());
        assertEquals("+00:00", result.rows().get(0).get(0));
        assertEquals("15000", result.rows().get(0).get(1));
        assertEquals("1", result.rows().get(0).get(2));
        try (Connection connection = handle.dataSource().getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet =
                        statement.executeQuery(
                                "SELECT @@session.time_zone, @@session.max_execution_time, "
                                        + "@@session.transaction_read_only, @@autocommit")) {
            assertTrue(resultSet.next());
            assertEquals("+00:00", resultSet.getString(1));
            assertEquals(0, resultSet.getLong(2));
            assertEquals(0, resultSet.getInt(3));
            assertEquals(1, resultSet.getInt(4));
        }
    }

    @Test
    void timeoutEvictsTheUntrustedConnectionAndTheNextRequestIsNotKilled() {
        String beforeId =
                executor.execute(
                                1,
                                request("SELECT CONNECTION_ID() AS id", 1),
                                new ReadOnlyCancellation())
                        .rows()
                        .get(0)
                        .get(0);
        TRACES.clear();
        MysqlReadOnlyExecutor shortBudget =
                new MysqlReadOnlyExecutor(
                        registry, new ExecutorSettings(1_000, 3_000, 2_000), TRACES::add);

        DomainException failure =
                assertThrows(
                        DomainException.class,
                        () ->
                                shortBudget.execute(
                                        1,
                                        request("SELECT SLEEP(5)", 1),
                                        new ReadOnlyCancellation()));

        assertEquals(ErrorCode.QUERY_TIMEOUT, failure.errorCode());
        assertEquals(1, TRACES.size());
        ExecutionTrace trace = TRACES.get(0);
        assertEquals(ExecutionState.EVICTED, trace.finalState());
        assertEquals(
                List.of(
                        ExecutionState.ACQUIRED,
                        ExecutionState.CONFIGURED,
                        ExecutionState.EXECUTING,
                        ExecutionState.CANCELLING,
                        ExecutionState.CLEANING,
                        ExecutionState.EVICTED),
                trace.states());
        assertEquals(1, handle.evictedConnections());

        ReadOnlyResult reused =
                executor.execute(
                        1,
                        request("SELECT CONNECTION_ID() AS id, 7 AS value", 1),
                        new ReadOnlyCancellation());
        assertEquals("7", reused.rows().get(0).get(1));
        assertNotEquals(beforeId, reused.rows().get(0).get(0));
        assertEquals(ExecutionState.RETURNED, reused.trace().finalState());
    }

    @Test
    void explicitCancellationStopsExecutionAndDoesNotPoisonTheNextQuery() throws Exception {
        long connectionId = connectionIdViaExecutor();
        TRACES.clear();
        ReadOnlyCancellation cancellation = new ReadOnlyCancellation();
        ExecutorService pool =
                new ThreadPoolExecutor(
                        1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
        try {
            Future<DomainException> future =
                    pool.submit(
                            () -> {
                                try {
                                    executor.execute(
                                            1, request("SELECT SLEEP(5)", 1), cancellation);
                                    return null;
                                } catch (DomainException failure) {
                                    return failure;
                                }
                            });
            Thread.sleep(300);
            cancellation.cancel();
            DomainException failure = future.get(10, TimeUnit.SECONDS);
            assertNotNull(failure);
            assertEquals(ErrorCode.CLIENT_CANCELLED, failure.errorCode());
        } finally {
            pool.shutdownNow();
        }
        assertEquals(ExecutionState.EVICTED, TRACES.get(0).finalState());
        assertEquals(
                List.of(
                        ExecutionState.ACQUIRED,
                        ExecutionState.CONFIGURED,
                        ExecutionState.EXECUTING,
                        ExecutionState.CANCELLING,
                        ExecutionState.CLEANING,
                        ExecutionState.EVICTED),
                TRACES.get(0).states());
        assertEquals(1, handle.evictedConnections());

        ReadOnlyResult next =
                executor.execute(
                        1,
                        request("SELECT CONNECTION_ID(), 42 AS value", 1),
                        new ReadOnlyCancellation());
        assertNotEquals(String.valueOf(connectionId), next.rows().get(0).get(0));
        assertEquals("42", next.rows().get(0).get(1));
        assertEquals(ExecutionState.RETURNED, next.trace().finalState());
    }

    @Test
    void dirtyRollbackEvictsTheConnectionInsteadOfReturningIt() throws Exception {
        registry.close();
        FailingRollbackDataSource failing =
                new FailingRollbackDataSource(
                        new HikariPoolFactory()
                                .create(key(9, 1), password.toCharArray(), PoolLimits.of(1)));
        registry = new DataSourcePoolRegistry((poolKey, credential, limits) -> failing);
        handle = registry.activate(key(9, 1), password.toCharArray(), PoolLimits.of(1));
        executor = new MysqlReadOnlyExecutor(registry, ExecutorSettings.defaults(), TRACES::add);

        DomainException failure =
                assertThrows(
                        DomainException.class,
                        () ->
                                executor.execute(
                                        9, request("SELECT 1", 1), new ReadOnlyCancellation()));

        assertEquals(ErrorCode.CLEANUP_FAILED, failure.errorCode());
        assertEquals(1, handle.evictedConnections());
        assertEquals(ExecutionState.EVICTED, TRACES.get(0).finalState());
        assertEquals(
                List.of(
                        ExecutionState.ACQUIRED,
                        ExecutionState.CONFIGURED,
                        ExecutionState.EXECUTING,
                        ExecutionState.DRAINING,
                        ExecutionState.CLEANING,
                        ExecutionState.EVICTED),
                TRACES.get(0).states());

        ReadOnlyResult next =
                executor.execute(
                        9, request("SELECT CONNECTION_ID() AS id", 1), new ReadOnlyCancellation());
        long secondConnectionId = Long.parseLong(next.rows().get(0).get(0));
        assertNotEquals(failing.firstConnectionId(), secondConnectionId);
        assertTrue(failing.firstConnectionId() > 0);
    }

    @Test
    void generationChangeDrainsTheOldPoolAndRoutesToTheNewOne() throws Exception {
        ReadOnlyResult first =
                executor.execute(1, request("SELECT 1 AS value", 1), new ReadOnlyCancellation());
        long firstGeneration = first.trace().poolGeneration();
        assertEquals(handle.generation(), firstGeneration);

        PoolHandle same = registry.activate(key(1, 1), password.toCharArray(), PoolLimits.of(1));
        assertEquals(handle, same);
        assertEquals(firstGeneration, same.generation());

        PoolHandle next = registry.activate(key(1, 2), password.toCharArray(), PoolLimits.of(1));
        assertEquals(PoolHandle.State.CLOSED, handle.state());
        assertTrue(next.generation() > firstGeneration);
        assertEquals(next, registry.current(1).orElseThrow());
        assertThrows(SQLException.class, () -> handle.dataSource().getConnection());

        ReadOnlyResult second =
                executor.execute(1, request("SELECT 2 AS value", 1), new ReadOnlyCancellation());
        assertEquals(next.generation(), second.trace().poolGeneration());
        assertEquals("2", second.rows().get(0).get(0));
    }

    @Test
    void activeGenerationLeaseKeepsTheOriginalPhysicalConnectionAliveDuringRotation()
            throws Exception {
        PoolHandle original = handle;
        PoolHandle next;
        try (PoolHandle.Lease lease = original.lease();
                Connection connection = lease.dataSource().getConnection();
                Statement statement = connection.createStatement()) {
            next = registry.activate(key(1, 2), password.toCharArray(), PoolLimits.of(1));
            assertEquals(PoolHandle.State.DRAINING, original.state());
            assertThrows(DomainException.class, original::lease);
            try (ResultSet result = statement.executeQuery("SELECT 1")) {
                assertTrue(result.next());
                assertEquals(1, result.getInt(1));
            }
        }
        assertEquals(PoolHandle.State.CLOSED, original.state());
        ReadOnlyResult result =
                executor.execute(1, request("SELECT 2 AS value", 1), new ReadOnlyCancellation());
        assertEquals(next.generation(), result.trace().poolGeneration());
        assertEquals("2", result.rows().get(0).get(0));
    }

    @Test
    void approvalExpiringDuringPhysicalCheckoutSubmitsNoUserSqlAndCleansTheConnection()
            throws Exception {
        registry.close();
        CheckoutExpiryProbe probe = new CheckoutExpiryProbe();
        registry =
                new DataSourcePoolRegistry(
                        (poolKey, secret, limits) ->
                                probe.wrap(
                                        new HikariPoolFactory().create(poolKey, secret, limits),
                                        true));
        registry.activate(key(1, 1), password.toCharArray(), PoolLimits.of(1));
        executor = new MysqlReadOnlyExecutor(registry, ExecutorSettings.defaults(), TRACES::add);
        ApprovedQueryAdapter adapter = new ApprovedQueryAdapter(executor, probe);
        DomainException failure =
                assertThrows(
                        DomainException.class,
                        () ->
                                adapter.execute(
                                        probe.plan(), ignored -> {}, new ReadOnlyCancellation()));
        assertEquals(ErrorCode.RESOURCE_VERSION_CHANGED, failure.errorCode());
        assertEquals(0, probe.executions());
        assertEquals(ExecutionState.RETURNED, TRACES.getLast().finalState());
    }

    @Test
    void rowLimitAndFetchSizeAreEnforced() throws Exception {
        ReadOnlyResult limited =
                executor.execute(
                        1,
                        request("SELECT id FROM probe ORDER BY id", 120),
                        new ReadOnlyCancellation());
        assertEquals(120, limited.rows().size());
        assertTrue(limited.limitReached());

        ReadOnlyResult all =
                executor.execute(
                        1,
                        request("SELECT id FROM probe ORDER BY id", 200),
                        new ReadOnlyCancellation());
        assertEquals(PROBE_ROWS, all.rows().size());
        assertFalse(all.limitReached());
        assertEquals(1, all.columns().size());
        assertEquals("id", all.columns().get(0).label());

        long before = statementFetchCount();
        executor.execute(
                1, request("SELECT id FROM probe ORDER BY id", 200), new ReadOnlyCancellation());
        long after = statementFetchCount();
        assertTrue(after > before, "server-side cursor fetches");

        assertThrows(IllegalArgumentException.class, () -> ReadOnlyRequest.of("SELECT 1", 0));
        assertThrows(IllegalArgumentException.class, () -> ReadOnlyRequest.of("SELECT 1", 501));
    }

    @Test
    void checkoutBudgetAndErrorsDoNotLeakSql() throws Exception {
        DataSourcePoolRegistry shortCheckout =
                new DataSourcePoolRegistry(
                        (poolKey, credential, limits) ->
                                new HikariPoolFactory()
                                        .create(poolKey, credential, new PoolLimits(1, 1_000)));
        PoolHandle shortHandle =
                shortCheckout.activate(key(2, 1), password.toCharArray(), new PoolLimits(1, 1_000));
        try {
            MysqlReadOnlyExecutor shortExecutor =
                    new MysqlReadOnlyExecutor(shortCheckout, ExecutorSettings.defaults());
            try (Connection occupied = shortHandle.dataSource().getConnection()) {
                DomainException failure =
                        assertThrows(
                                DomainException.class,
                                () ->
                                        shortExecutor.execute(
                                                2,
                                                request("SELECT 1", 1),
                                                new ReadOnlyCancellation()));
                assertEquals(ErrorCode.CONNECTION_POOL_UNAVAILABLE, failure.errorCode());
            }
        } finally {
            shortCheckout.close();
        }

        DomainException failure =
                assertThrows(
                        DomainException.class,
                        () ->
                                executor.execute(
                                        1,
                                        request("SELECT * FROM missing_probe", 1),
                                        new ReadOnlyCancellation()));
        assertEquals(ErrorCode.DRIVER_ERROR, failure.errorCode());
        assertFalse(failure.getMessage().contains("missing_probe"));
        assertFalse(failure.getMessage().contains("SQLSyntaxErrorException"));
        assertFalse(failure.getMessage().contains("SELECT"));
    }

    private long connectionIdViaExecutor() {
        ReadOnlyResult result =
                executor.execute(
                        1, request("SELECT CONNECTION_ID() AS id", 1), new ReadOnlyCancellation());
        return Long.parseLong(result.rows().get(0).get(0));
    }

    private static ReadOnlyRequest request(String sql, int maxRows) {
        return ReadOnlyRequest.of(sql, maxRows);
    }

    private long statementFetchCount() throws SQLException {
        try (Connection connection = handle.dataSource().getConnection();
                Statement statement = connection.createStatement();
                ResultSet resultSet =
                        statement.executeQuery("SHOW SESSION STATUS LIKE 'Com_stmt_fetch'")) {
            assertTrue(resultSet.next());
            return resultSet.getLong(2);
        }
    }

    private static PoolKey key(long datasourceId, long configVersion) {
        return new PoolKey(
                datasourceId,
                configVersion,
                DatabaseEngine.MYSQL,
                mysql.getHost(),
                mysql.getMappedPort(3306),
                mysql.getDatabaseName(),
                mysql.getUsername(),
                configVersion);
    }

    private static final class FailingRollbackDataSource implements DataSource, AutoCloseable {
        private final DataSource delegate;
        private final AtomicBoolean failNextRollback = new AtomicBoolean(true);
        private final AtomicLong firstConnectionId = new AtomicLong(-1);

        FailingRollbackDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        long firstConnectionId() {
            return firstConnectionId.get();
        }

        @Override
        public Connection getConnection() throws SQLException {
            Connection connection = delegate.getConnection();
            if (failNextRollback.getAndSet(false)) {
                firstConnectionId.set(connectionId(connection));
                return failingRollback(connection);
            }
            return connection;
        }

        @Override
        public Connection getConnection(String username, String credential) throws SQLException {
            return delegate.getConnection(username, credential);
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getLogger("ptmcp-test");
        }

        @Override
        public <T> T unwrap(Class<T> type) throws SQLException {
            return delegate.unwrap(type);
        }

        @Override
        public boolean isWrapperFor(Class<?> type) throws SQLException {
            return delegate.isWrapperFor(type);
        }

        @Override
        public void close() throws Exception {
            if (delegate instanceof AutoCloseable closeable) {
                closeable.close();
            }
        }

        private static long connectionId(Connection connection) throws SQLException {
            try (Statement statement = connection.createStatement();
                    ResultSet resultSet = statement.executeQuery("SELECT CONNECTION_ID()")) {
                assertTrue(resultSet.next());
                return resultSet.getLong(1);
            }
        }

        private static Connection failingRollback(Connection connection) {
            return (Connection)
                    Proxy.newProxyInstance(
                            Connection.class.getClassLoader(),
                            new Class<?>[] {Connection.class},
                            (proxy, method, arguments) -> {
                                if ("rollback".equals(method.getName())
                                        && (arguments == null || arguments.length == 0)) {
                                    throw new SQLException("injected rollback failure", "08000");
                                }
                                try {
                                    return method.invoke(connection, arguments);
                                } catch (InvocationTargetException failure) {
                                    throw failure.getCause();
                                }
                            });
        }
    }

    @Test
    void poolLimitsAndReadOnlyRequestsValidateTheirBounds() {
        assertThrows(IllegalArgumentException.class, () -> new PoolLimits(0, 3_000));
        assertThrows(IllegalArgumentException.class, () -> new PoolLimits(9, 3_000));
        assertThrows(IllegalArgumentException.class, () -> new PoolLimits(1, 100));
        assertThrows(IllegalArgumentException.class, () -> new ExecutorSettings(0, 3_000, 2_000));
        assertThrows(
                IllegalArgumentException.class, () -> new ExecutorSettings(60_001, 3_000, 2_000));
    }
}
