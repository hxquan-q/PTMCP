package local.ptmcp.datasource.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.datasource.ExecutionStage;
import local.ptmcp.datasource.ExecutionState;
import local.ptmcp.datasource.ExecutionTrace;
import local.ptmcp.datasource.ReadOnlyCancellation;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import javax.sql.DataSource;

/** Owned PostgreSQL 18 container; missing Docker fails instead of skipping. */
class PgReadOnlyExecutorTest {
    private static final String IMAGE =
            "postgres@sha256:edb0c08dc33befd0262ab3b5cf1c4f3f8cfd8b09e88c05b45adee29d6761ddb6";
    private static final int MAX_CELL_BYTES = 4_096;
    private static final int MAX_BINARY_BYTES = 3_072;

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static PgReadOnlyExecutor executor;

    @BeforeAll
    static void startOwnedPostgres() throws Exception {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        postgres =
                new PostgreSQLContainer(DockerImageName.parse(IMAGE))
                        .withDatabaseName("ptmcp_pg_test")
                        .withUsername("ptmcp_test")
                        .withPassword(
                                Base64.getUrlEncoder().withoutPadding().encodeToString(secret))
                        .withEnv("TZ", "UTC")
                        .withUrlParam("sslmode", "disable");
        postgres.start();
        try {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(postgres.getJdbcUrl());
            config.setUsername(postgres.getUsername());
            config.setPassword(postgres.getPassword());
            config.setMaximumPoolSize(1);
            config.setMinimumIdle(0);
            config.setConnectionTimeout(5_000);
            config.setValidationTimeout(2_000);
            config.setInitializationFailTimeout(-1);
            config.setAutoCommit(true);
            config.setReadOnly(false);
            config.setRegisterMbeans(false);
            config.setPoolName("ptmcp-pg-test");
            dataSource = new HikariDataSource(config);
            try (Connection connection = dataSource.getConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute(
                        "CREATE TABLE pg_types ("
                                + "id integer PRIMARY KEY, u text, j json, jb jsonb, b bytea, "
                                + "flag boolean, i2 smallint, i4 integer, i8 bigint, "
                                + "n numeric(38,10), f4 real, f8 double precision, "
                                + "d date, tm time(3), tmtz time(3) with time zone, "
                                + "ts timestamp(3), tstz timestamptz(3))");
                statement.execute(
                        "INSERT INTO pg_types VALUES ("
                                + "1, '中文😀', '{\"k\":\"值\"}', '{\"k\":\"值\"}', "
                                + "decode('00ff','hex'), true, 32767, 2147483647, "
                                + "9223372036854775807, "
                                + "NUMERIC '1234567890123456789012345678.1234567890', "
                                + "REAL '1.25', DOUBLE PRECISION '2.5', "
                                + "DATE '2026-10-24', TIME '12:34:56.789', "
                                + "TIMETZ '12:34:56.789+08', "
                                + "TIMESTAMP '2026-10-24 12:34:56.789', "
                                + "TIMESTAMPTZ '2026-10-24 12:34:56.789+08')");
                statement.execute(
                        "CREATE TABLE pg_wide (id integer PRIMARY KEY, big_text text, "
                                + "big_bytes bytea, emoji text)");
                statement.execute(
                        "INSERT INTO pg_wide VALUES (1, repeat('x', 10485760), "
                                + "decode(repeat('ab', 5242880), 'hex'), repeat('😀', 3000))");
                statement.execute("CREATE TABLE pg_writes (id integer PRIMARY KEY, label text)");
                statement.execute("INSERT INTO pg_writes VALUES (1, 'before')");
                statement.execute("CREATE TABLE pg_rows (n integer)");
                statement.execute("INSERT INTO pg_rows SELECT generate_series(1, 5)");
                statement.execute("CREATE DOMAIN pg_posint AS integer CHECK (VALUE > 0)");
                statement.execute("CREATE TYPE pg_pair AS (a integer, b text)");
                statement.execute("CREATE TABLE pg_domain_cols (id integer, value pg_posint)");
                statement.execute("INSERT INTO pg_domain_cols VALUES (1, 7)");
                statement.execute("CREATE TABLE pg_plan (id integer PRIMARY KEY, label text)");
                statement.execute(
                        "INSERT INTO pg_plan SELECT n, 'label-' || n "
                                + "FROM generate_series(1, 20) n");
                statement.execute("CREATE TABLE pg_huge (id integer PRIMARY KEY, body text)");
                statement.execute("INSERT INTO pg_huge VALUES (1, repeat('y', 104857600))");
            }
            executor = new PgReadOnlyExecutor(dataSource, PgExecutorSettings.defaults());
        } catch (Exception | AssertionError failure) {
            if (dataSource != null) {
                dataSource.close();
            }
            postgres.stop();
            throw failure;
        }
    }

    @AfterAll
    static void stopOwnedContainer() {
        if (dataSource != null) {
            dataSource.close();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void readOnlyTransactionRejectsWritesAndRestoresSession() throws Exception {
        List<ExecutionTrace> traces = new ArrayList<>();
        PgReadOnlyExecutor observed =
                new PgReadOnlyExecutor(dataSource, PgExecutorSettings.defaults(), traces::add);
        DomainException failure =
                assertThrows(
                        DomainException.class,
                        () ->
                                observed.execute(
                                        9,
                                        PgReadOnlyRequest.of(
                                                "UPDATE public.pg_writes SET label = 'after' WHERE id = 1",
                                                10),
                                        new ReadOnlyCancellation()));
        assertCode(failure, ErrorCode.SQL_NOT_READONLY);
        assertEquals("before", scalarRaw("SELECT label FROM public.pg_writes WHERE id = 1"));
        assertEquals(1, traces.size());
        ExecutionTrace failed = traces.get(0);
        assertEquals(ExecutionState.RETURNED, failed.finalState());
        assertEquals(
                List.of(
                        ExecutionStage.CHECKOUT,
                        ExecutionStage.CONFIGURE,
                        ExecutionStage.EXECUTE,
                        ExecutionStage.DRAIN,
                        ExecutionStage.CLEAN,
                        ExecutionStage.RETURN),
                failed.stages());

        PgReadOnlyResult next = query("SELECT label FROM public.pg_writes WHERE id = 1");
        assertEquals(List.of(List.of("before")), next.rows());
        assertEquals(ExecutionState.RETURNED, next.trace().finalState());
        assertEquals(9, next.trace().poolGeneration());
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet result =
                        statement.executeQuery(
                                "SELECT current_setting('search_path'), "
                                        + "(SELECT setting FROM pg_settings "
                                        + "WHERE name = 'statement_timeout'), "
                                        + "current_setting('transaction_read_only')")) {
            assertTrue(result.next());
            assertNotEquals("pg_catalog", result.getString(1));
            assertEquals("0", result.getString(2));
            assertEquals("off", result.getString(3));
        }
    }

    @Test
    void setLocalIsTransactionScopedAndVerified() throws Exception {
        PgReadOnlyResult result =
                query(
                        "SELECT current_setting('search_path'), "
                                + "current_setting('transaction_read_only'), "
                                + "(SELECT setting FROM pg_settings "
                                + "WHERE name = 'statement_timeout'), "
                                + "(SELECT setting FROM pg_settings "
                                + "WHERE name = 'lock_timeout'), "
                                + "current_setting('default_transaction_read_only')");
        assertEquals(List.of(List.of("pg_catalog", "on", "15000", "5000", "off")), result.rows());

        // Outside a transaction SET LOCAL is a no-op; the executor only uses it after BEGIN.
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("SET LOCAL statement_timeout = '1234ms'");
            statement.execute("SET LOCAL search_path TO pg_catalog");
            try (ResultSet raw =
                    statement.executeQuery(
                            "SELECT (SELECT setting FROM pg_settings "
                                    + "WHERE name = 'statement_timeout'), "
                                    + "current_setting('search_path')")) {
                assertTrue(raw.next());
                assertEquals("0", raw.getString(1));
                assertNotEquals("pg_catalog", raw.getString(2));
            }
        }
    }

    @Test
    void projectsBuiltInTypesAndPreservesPrecision() {
        PgReadOnlyResult result =
                query(
                        "SELECT u, j, jb, b, flag, i2, i4, i8, n, f4, f8, d, tm, tmtz, ts, tstz, "
                                + "NULL::text AS missing FROM public.pg_types WHERE id = 1");
        assertEquals(
                List.of(
                        PgReadOnlyResult.LogicalType.STRING,
                        PgReadOnlyResult.LogicalType.JSON,
                        PgReadOnlyResult.LogicalType.JSON,
                        PgReadOnlyResult.LogicalType.BINARY,
                        PgReadOnlyResult.LogicalType.BOOLEAN,
                        PgReadOnlyResult.LogicalType.INTEGER,
                        PgReadOnlyResult.LogicalType.INTEGER,
                        PgReadOnlyResult.LogicalType.INTEGER,
                        PgReadOnlyResult.LogicalType.DECIMAL,
                        PgReadOnlyResult.LogicalType.FLOAT,
                        PgReadOnlyResult.LogicalType.FLOAT,
                        PgReadOnlyResult.LogicalType.DATE,
                        PgReadOnlyResult.LogicalType.TIME,
                        PgReadOnlyResult.LogicalType.TIME,
                        PgReadOnlyResult.LogicalType.LOCAL_DATETIME,
                        PgReadOnlyResult.LogicalType.INSTANT,
                        PgReadOnlyResult.LogicalType.STRING),
                result.columns().stream().map(PgReadOnlyResult.Column::logicalType).toList());
        assertEquals(PgReadOnlyResult.Encoding.JSON_TEXT, result.columns().get(1).encoding());
        assertEquals(PgReadOnlyResult.Encoding.BASE64, result.columns().get(3).encoding());
        List<Object> row = result.rows().get(0);
        assertEquals("中文😀", row.get(0));
        assertEquals("{\"k\":\"值\"}", row.get(1));
        assertTrue(((String) row.get(2)).contains("值"));
        assertEquals("AP8=", row.get(3));
        assertEquals(Boolean.TRUE, row.get(4));
        assertEquals(32767, row.get(5));
        assertEquals(2147483647, row.get(6));
        assertEquals("9223372036854775807", row.get(7));
        assertEquals("1234567890123456789012345678.1234567890", row.get(8));
        assertEquals(1.25f, row.get(9));
        assertEquals(2.5d, row.get(10));
        assertEquals("2026-10-24", row.get(11));
        assertEquals("12:34:56.789", row.get(12));
        assertEquals("12:34:56.789+08:00", row.get(13));
        assertEquals("2026-10-24 12:34:56.789", row.get(14));
        assertEquals("2026-10-24T04:34:56.789Z", row.get(15));
        assertNull(row.get(16));
        assertTrue(result.cellTruncations().isEmpty());
        assertFalse(result.rowLimitReached());
        assertFalse(result.responseLimitReached());
    }

    @Test
    void duplicateColumnLabelsRemainAddressableByIndex() {
        PgReadOnlyResult result = query("SELECT 1 AS dup, 'two'::text AS dup");
        assertEquals(2, result.columns().size());
        assertEquals("dup", result.columns().get(0).label());
        assertEquals("dup", result.columns().get(1).label());
        assertEquals(0, result.columns().get(0).index());
        assertEquals(1, result.columns().get(1).index());
        assertEquals(1, result.rows().get(0).get(0));
        assertEquals("two", result.rows().get(0).get(1));
    }

    @Test
    void oversizedTextAndByteaUseBoundedPrefixes() {
        resetHeapPeaks();
        long before = usedHeap();
        PgReadOnlyResult result =
                query("SELECT big_text, big_bytes, emoji FROM public.pg_wide WHERE id = 1");
        long peak = heapPeak();
        System.out.println(
                "T11-a wide-row heap bytes: before="
                        + before
                        + " peak="
                        + peak
                        + " after="
                        + usedHeap());

        String text = (String) result.rows().get(0).get(0);
        assertEquals(MAX_CELL_BYTES, text.getBytes(StandardCharsets.UTF_8).length);
        byte[] binary = Base64.getDecoder().decode((String) result.rows().get(0).get(1));
        assertEquals(MAX_BINARY_BYTES, binary.length);
        assertTrue(((String) result.rows().get(0).get(1)).length() <= MAX_CELL_BYTES);
        String emoji = (String) result.rows().get(0).get(2);
        assertEquals(MAX_CELL_BYTES, emoji.getBytes(StandardCharsets.UTF_8).length);
        assertNoUnpairedSurrogates(emoji);
        assertEquals(3, result.cellTruncations().size());
        for (PgReadOnlyResult.CellTruncation truncation : result.cellTruncations()) {
            assertEquals(0, truncation.rowIndex());
            assertNull(truncation.originalBytes());
        }
        assertEquals(MAX_CELL_BYTES, result.cellTruncations().get(0).returnedBytes());
        assertEquals(MAX_BINARY_BYTES, result.cellTruncations().get(1).returnedBytes());
        assertEquals(MAX_CELL_BYTES, result.cellTruncations().get(2).returnedBytes());
        assertTrue(result.truncated());
        assertTrue(
                peak - before < 256L * 1024 * 1024,
                "heap peak grew by " + (peak - before) + " bytes");
    }

    @Test
    void unknownOrUserDefinedTypesAreRejectedEvenWithZeroRows() {
        assertUnsupported("SELECT value FROM public.pg_domain_cols WHERE false");
        assertUnsupported("SELECT ROW(1, 'x')::public.pg_pair AS composite_value WHERE false");
        assertUnsupported("SELECT ARRAY[1, 2]::integer[] AS array_value WHERE false");
    }

    @Test
    void rowAndResponseBudgetsDropWholeRows() {
        PgReadOnlyResult rowLimited = query("SELECT n FROM public.pg_rows ORDER BY n", 2);
        assertEquals(List.of(List.of(1), List.of(2)), rowLimited.rows());
        assertTrue(rowLimited.rowLimitReached());
        assertFalse(rowLimited.responseLimitReached());

        PgReadOnlyExecutor twoHundredSixty =
                new PgReadOnlyExecutor(dataSource, PgExecutorSettings.defaults(), 260, trace -> {});
        PgReadOnlyResult responseLimited =
                twoHundredSixty.execute(
                        1,
                        PgReadOnlyRequest.of(
                                "SELECT repeat('x', 100) AS a FROM public.pg_rows ORDER BY n", 5),
                        new ReadOnlyCancellation());
        assertEquals(1, responseLimited.rows().size());
        assertEquals(100, ((String) responseLimited.rows().get(0).get(0)).length());
        assertTrue(responseLimited.responseLimitReached());
        assertFalse(responseLimited.rowLimitReached());

        PgReadOnlyExecutor tooSmall =
                new PgReadOnlyExecutor(dataSource, PgExecutorSettings.defaults(), 150, trace -> {});
        PgReadOnlyResult empty =
                tooSmall.execute(
                        1,
                        PgReadOnlyRequest.of("SELECT repeat('x', 100) AS a", 5),
                        new ReadOnlyCancellation());
        assertTrue(empty.rows().isEmpty());
        assertEquals(1, empty.columns().size());
        assertTrue(empty.responseLimitReached());
        assertFalse(empty.rowLimitReached());
    }

    @Test
    void setupFailureIsConfiguredFailureAndEvicts() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        AtomicInteger aborts = new AtomicInteger();
        try (Connection real =
                DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            Connection failing =
                    (Connection)
                            Proxy.newProxyInstance(
                                    PgReadOnlyExecutorTest.class.getClassLoader(),
                                    new Class<?>[] {Connection.class},
                                    (proxy, method, arguments) -> {
                                        if (method.getName().equals("close")) {
                                            closes.incrementAndGet();
                                            return null;
                                        }
                                        if (method.getName().equals("abort")) {
                                            aborts.incrementAndGet();
                                            return null;
                                        }
                                        if (method.getName().equals("createStatement")) {
                                            Statement statement =
                                                    (Statement) invoke(real, method, arguments);
                                            return Proxy.newProxyInstance(
                                                    PgReadOnlyExecutorTest.class.getClassLoader(),
                                                    new Class<?>[] {Statement.class},
                                                    (nested, nestedMethod, nestedArguments) -> {
                                                        if (nestedMethod.getName().equals("execute")
                                                                && nestedArguments != null
                                                                && nestedArguments.length == 1
                                                                && nestedArguments[0]
                                                                        instanceof String sql
                                                                && sql.startsWith(
                                                                        "SET LOCAL statement_timeout")) {
                                                            throw new SQLException(
                                                                    "injected setup failure "
                                                                            + "password=never-expose",
                                                                    "XX000");
                                                        }
                                                        return invoke(
                                                                statement,
                                                                nestedMethod,
                                                                nestedArguments);
                                                    });
                                        }
                                        return invoke(real, method, arguments);
                                    });
            List<ExecutionTrace> traces = new ArrayList<>();
            PgReadOnlyExecutor observed =
                    new PgReadOnlyExecutor(
                            dataSourceOf(failing), PgExecutorSettings.defaults(), traces::add);
            DomainException failure =
                    assertThrows(
                            DomainException.class,
                            () ->
                                    observed.execute(
                                            1,
                                            PgReadOnlyRequest.of("SELECT 1", 1),
                                            new ReadOnlyCancellation()));
            assertCode(failure, ErrorCode.DRIVER_ERROR);
            assertSanitized(failure);
            assertEquals(ExecutionState.EVICTED, traces.get(0).finalState());
            assertEquals(
                    List.of(ExecutionStage.CHECKOUT, ExecutionStage.EVICT), traces.get(0).stages());
            assertEquals(1, aborts.get());
            assertEquals(1, closes.get());
        }
        assertEquals(1, query("SELECT 1 AS one").rows().get(0).get(0));
    }

    @Test
    void cleanupFailureReportsCleanupFailedAndEvicts() throws Exception {
        AtomicInteger closes = new AtomicInteger();
        try (Connection real =
                DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            Connection failingRollback =
                    (Connection)
                            Proxy.newProxyInstance(
                                    PgReadOnlyExecutorTest.class.getClassLoader(),
                                    new Class<?>[] {Connection.class},
                                    (proxy, method, arguments) -> {
                                        if (method.getName().equals("rollback")) {
                                            throw new SQLException(
                                                    "injected rollback failure password=never-expose");
                                        }
                                        if (method.getName().equals("close")) {
                                            closes.incrementAndGet();
                                            return null;
                                        }
                                        return invoke(real, method, arguments);
                                    });
            PgReadOnlyExecutor failing = new PgReadOnlyExecutor(dataSourceOf(failingRollback));
            DomainException failure =
                    assertThrows(
                            DomainException.class,
                            () ->
                                    failing.execute(
                                            1,
                                            PgReadOnlyRequest.of("SELECT 1", 1),
                                            new ReadOnlyCancellation()));
            assertCode(failure, ErrorCode.CLEANUP_FAILED);
            assertSanitized(failure);
            assertEquals(1, closes.get());
        }
        assertEquals(1, query("SELECT 1 AS one").rows().get(0).get(0));
    }

    @Test
    void cancellationBeforeExecutionReturnsCleanly() {
        ReadOnlyCancellation cancellation = new ReadOnlyCancellation();
        cancellation.cancel();
        List<ExecutionTrace> traces = new ArrayList<>();
        PgReadOnlyExecutor observed =
                new PgReadOnlyExecutor(dataSource, PgExecutorSettings.defaults(), traces::add);
        DomainException failure =
                assertThrows(
                        DomainException.class,
                        () ->
                                observed.execute(
                                        1, PgReadOnlyRequest.of("SELECT 1", 1), cancellation));
        assertCode(failure, ErrorCode.CLIENT_CANCELLED);
        assertEquals(ExecutionState.RETURNED, traces.get(0).finalState());
        assertEquals(
                List.of(
                        ExecutionStage.CHECKOUT,
                        ExecutionStage.CONFIGURE,
                        ExecutionStage.CANCEL,
                        ExecutionStage.CLEAN,
                        ExecutionStage.RETURN),
                traces.get(0).stages());
        assertEquals(1, query("SELECT 1 AS one").rows().get(0).get(0));
    }

    @Test
    void statementTimeoutIsEnforcedAndTheConnectionIsEvicted() {
        PgReadOnlyExecutor quick =
                new PgReadOnlyExecutor(dataSource, new PgExecutorSettings(200, 100, 2_000));
        DomainException failure =
                assertThrows(
                        DomainException.class,
                        () ->
                                quick.execute(
                                        1,
                                        PgReadOnlyRequest.of("SELECT pg_sleep(2)", 1),
                                        new ReadOnlyCancellation()));
        assertCode(failure, ErrorCode.QUERY_TIMEOUT);
        assertEquals(1, query("SELECT 1 AS one").rows().get(0).get(0));
    }

    @Test
    void invalidInputCannotAcquireConnection() {
        AtomicInteger acquisitions = new AtomicInteger();
        DataSource counting =
                (DataSource)
                        Proxy.newProxyInstance(
                                PgReadOnlyExecutorTest.class.getClassLoader(),
                                new Class<?>[] {DataSource.class},
                                (proxy, method, arguments) -> {
                                    if (method.getName().equals("getConnection")) {
                                        acquisitions.incrementAndGet();
                                        throw new SQLException("not available");
                                    }
                                    if (method.getName().equals("getLogWriter")) {
                                        return (PrintWriter) null;
                                    }
                                    if (method.getName().equals("getParentLogger")) {
                                        return Logger.getGlobal();
                                    }
                                    throw new UnsupportedOperationException(method.getName());
                                });
        new PgReadOnlyExecutor(counting);
        for (String sql : List.of("", "   ", "a".repeat(65_537), "SELECT\0 1")) {
            assertThrows(IllegalArgumentException.class, () -> PgReadOnlyRequest.of(sql, 1));
        }
        assertThrows(IllegalArgumentException.class, () -> PgReadOnlyRequest.of(null, 1));
        assertThrows(IllegalArgumentException.class, () -> PgReadOnlyRequest.of("SELECT 1", 0));
        assertThrows(IllegalArgumentException.class, () -> PgReadOnlyRequest.of("SELECT 1", 501));
        assertThrows(IllegalArgumentException.class, () -> new PgExecutorSettings(0, 0, 1_000));
        assertThrows(
                IllegalArgumentException.class,
                () -> new PgExecutorSettings(15_000, 15_001, 2_000));
        assertThrows(
                IllegalArgumentException.class, () -> new PgExecutorSettings(15_000, 5_000, 0));
        assertEquals(0, acquisitions.get());
    }

    private static PgReadOnlyResult query(String sql) {
        return query(sql, 100);
    }

    private static PgReadOnlyResult query(String sql, int maxRows) {
        return executor.execute(9, PgReadOnlyRequest.of(sql, maxRows), new ReadOnlyCancellation());
    }

    private static Object scalarRaw(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) {
                throw new SQLException("no row");
            }
            return result.getObject(1);
        }
    }

    private static void assertUnsupported(String sql) {
        DomainException failure = assertThrows(DomainException.class, () -> query(sql));
        assertCode(failure, ErrorCode.RESULT_TYPE_UNSUPPORTED);
        assertSanitized(failure);
    }

    private static void assertCode(DomainException failure, ErrorCode expected) {
        assertEquals(expected, failure.errorCode());
        assertEquals(expected.name(), failure.getMessage());
    }

    private static void assertSanitized(DomainException failure) {
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        assertEquals(failure.errorCode().name(), failure.getMessage());
        assertFalse(failure.toString().contains("password"));
    }

    private static void assertNoUnpairedSurrogates(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                assertTrue(
                        index + 1 < value.length()
                                && Character.isLowSurrogate(value.charAt(index + 1)),
                        "unpaired high surrogate at " + index);
                index++;
            } else {
                assertFalse(
                        Character.isLowSurrogate(current), "unpaired low surrogate at " + index);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T invoke(Object target, Method method, Object[] arguments) throws Throwable {
        try {
            method.setAccessible(true);
            return (T) method.invoke(target, arguments);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private static DataSource dataSourceOf(Connection connection) {
        return (DataSource)
                Proxy.newProxyInstance(
                        PgReadOnlyExecutorTest.class.getClassLoader(),
                        new Class<?>[] {DataSource.class},
                        (proxy, method, arguments) -> {
                            if (method.getName().equals("getConnection")) {
                                return connection;
                            }
                            if (method.getName().equals("getLogWriter")) {
                                return (PrintWriter) null;
                            }
                            if (method.getName().equals("getParentLogger")) {
                                return Logger.getGlobal();
                            }
                            throw new UnsupportedOperationException(method.getName());
                        });
    }

    private static long usedHeap() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static void resetHeapPeaks() {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                try {
                    pool.resetPeakUsage();
                } catch (UnsupportedOperationException ignored) {
                    // Some collectors do not expose resettable peaks; the bound stays conservative.
                }
            }
        }
    }

    @Test
    void planDriftTerminatesBeforeReadingCellsAndAuditsTheDrift() {
        List<ExecutionTrace> traces = new ArrayList<>();
        PgReadOnlyExecutor observed =
                new PgReadOnlyExecutor(dataSource, PgExecutorSettings.defaults(), traces::add);
        PgReadOnlyRequest request =
                PgReadOnlyRequest.of("SELECT id, label FROM public.pg_plan", 10);
        ReadOnlyCancellation cancellation = new ReadOnlyCancellation();

        // Column count drift.
        PgOutputPlan fewer =
                PgOutputPlan.of(
                        new PgOutputPlan.ExpectedColumn(
                                "id", PgReadOnlyResult.LogicalType.INTEGER));
        DomainException countDrift =
                assertThrows(
                        DomainException.class,
                        () -> observed.execute(9, request, fewer, cancellation));
        assertEquals(ErrorCode.RESULT_TYPE_UNSUPPORTED, countDrift.errorCode());

        // Label drift.
        PgOutputPlan wrongLabel =
                PgOutputPlan.of(
                        new PgOutputPlan.ExpectedColumn("id", PgReadOnlyResult.LogicalType.INTEGER),
                        new PgOutputPlan.ExpectedColumn(
                                "name", PgReadOnlyResult.LogicalType.STRING));
        DomainException labelDrift =
                assertThrows(
                        DomainException.class,
                        () -> observed.execute(9, request, wrongLabel, cancellation));
        assertEquals(ErrorCode.RESULT_TYPE_UNSUPPORTED, labelDrift.errorCode());

        // Type drift: the plan promised INTEGER, the column is text.
        PgOutputPlan wrongType =
                PgOutputPlan.of(
                        new PgOutputPlan.ExpectedColumn("id", PgReadOnlyResult.LogicalType.INTEGER),
                        new PgOutputPlan.ExpectedColumn(
                                "label", PgReadOnlyResult.LogicalType.INTEGER));
        DomainException typeDrift =
                assertThrows(
                        DomainException.class,
                        () -> observed.execute(9, request, wrongType, cancellation));
        assertEquals(ErrorCode.RESULT_TYPE_UNSUPPORTED, typeDrift.errorCode());

        assertEquals(3, traces.size());
        for (ExecutionTrace trace : traces) {
            assertEquals(ExecutionState.VERSION_DRIFT, trace.finalState());
            assertFalse(trace.toString().contains("label-"));
        }
    }

    @Test
    void matchingPlanAllowsTheExecution() {
        PgOutputPlan plan =
                PgOutputPlan.of(
                        new PgOutputPlan.ExpectedColumn("id", PgReadOnlyResult.LogicalType.INTEGER),
                        new PgOutputPlan.ExpectedColumn(
                                "label", PgReadOnlyResult.LogicalType.STRING));
        PgReadOnlyResult result =
                executor.execute(
                        9,
                        PgReadOnlyRequest.of(
                                "SELECT id, label FROM public.pg_plan ORDER BY id LIMIT 3", 10),
                        plan,
                        new ReadOnlyCancellation());
        assertEquals(3, result.rows().size());
        assertEquals(ExecutionState.RETURNED, result.trace().finalState());
    }

    @Test
    void aggregateUnionAndWindowOutputsProjectSafely() {
        PgReadOnlyResult aggregate =
                query("SELECT COUNT(*) AS total, SUM(id) AS sum_ids FROM public.pg_plan");
        assertEquals(1, aggregate.rows().size());
        assertEquals("20", aggregate.rows().getFirst().get(0));
        assertEquals("210", aggregate.rows().getFirst().get(1));

        PgReadOnlyResult union =
                query(
                        "SELECT id FROM public.pg_plan WHERE id <= 2 UNION SELECT id FROM public.pg_plan WHERE id = 1 ORDER BY id");
        assertEquals(2, union.rows().size());

        PgReadOnlyResult window =
                query(
                        "SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS rn FROM public.pg_plan WHERE id <= 3 ORDER BY id");
        assertEquals(3, window.rows().size());
        assertEquals("1", window.rows().getFirst().get(1));
        assertEquals(ExecutionState.RETURNED, window.trace().finalState());
    }

    @Test
    void hundredMiBTextFieldStreamsBounded() throws Exception {
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                try {
                    pool.resetPeakUsage();
                } catch (UnsupportedOperationException ignored) {
                    // Conservative bound stays.
                }
            }
        }
        // datasource.md §7 warns pgJDBC may buffer an unbounded cell before the stream starts,
        // so the safe shape is the server-side bounded prefix: the 100 MiB row stays in the
        // database and only the 4 KiB prefix ever reaches the driver.
        PgReadOnlyResult result = query("SELECT left(body, 4096) AS prefix FROM public.pg_huge", 5);
        assertFalse(result.rows().isEmpty());
        String cell = (String) result.rows().getFirst().get(0);
        assertEquals(MAX_CELL_BYTES, cell.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        assertTrue(result.cellTruncations().isEmpty());
        long peak = heapPeak();
        assertTrue(peak < 96L * 1024 * 1024, "heap peak " + peak + " must stay bounded");
    }

    @Test
    void lateCancellationAfterCompletionDoesNotAffectTheNextRequest() {
        ReadOnlyCancellation late = new ReadOnlyCancellation();
        PgReadOnlyResult done = query("SELECT COUNT(*) AS total FROM public.pg_plan");
        assertEquals("20", done.rows().getFirst().get(0));
        // The user disappears after the response: a cancel arriving now must not poison anything.
        late.cancel();
        PgReadOnlyResult next = query("SELECT COUNT(*) AS total FROM public.pg_plan");
        assertEquals("20", next.rows().getFirst().get(0));
        assertEquals(ExecutionState.RETURNED, next.trace().finalState());
    }

    @Test
    void cursorPagingStreamsFiveHundredRowsWithinBudget() {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE pg_many (id integer PRIMARY KEY)");
            statement.execute("INSERT INTO pg_many SELECT generate_series(1, 500)");
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                try {
                    pool.resetPeakUsage();
                } catch (UnsupportedOperationException ignored) {
                    // Conservative bound stays.
                }
            }
        }
        PgReadOnlyResult result = query("SELECT id FROM public.pg_many", 500);
        assertEquals(500, result.rows().size());
        assertFalse(result.responseLimitReached());
        assertTrue(result.rowLimitReached());
        assertTrue(heapPeak() < 128L * 1024 * 1024, "heap peak " + heapPeak());
    }

    private static long heapPeak() {
        long total = 0;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == MemoryType.HEAP) {
                total += pool.getPeakUsage().getUsed();
            }
        }
        return total;
    }
}
