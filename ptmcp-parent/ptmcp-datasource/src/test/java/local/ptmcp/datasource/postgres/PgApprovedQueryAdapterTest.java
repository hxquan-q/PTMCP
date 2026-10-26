package local.ptmcp.datasource.postgres;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.common.execution.ApprovedSqlPlan;
import local.ptmcp.common.resource.ResourceId;
import local.ptmcp.datasource.ReadOnlyCancellation;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

/** The PG bridge keeps the same hash/window/checkout guarantees as the MySQL adapter. */
class PgApprovedQueryAdapterTest {
    private static final String IMAGE =
            "postgres@sha256:edb0c08dc33befd0262ab3b5cf1c4f3f8cfd8b09e88c05b45adee29d6761ddb6";
    private static final Instant NOW = Instant.parse("2026-10-26T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static PgApprovedQueryAdapter adapter;

    @BeforeAll
    static void startOwnedPostgres() throws Exception {
        byte[] secret = new byte[32];
        new SecureRandom().nextBytes(secret);
        postgres =
                new PostgreSQLContainer(DockerImageName.parse(IMAGE))
                        .withDatabaseName("ptmcp_pg_adapter")
                        .withUsername("ptmcp_adapter")
                        .withPassword(
                                Base64.getUrlEncoder().withoutPadding().encodeToString(secret))
                        .withEnv("TZ", "UTC")
                        .withUrlParam("sslmode", "disable");
        postgres.start();
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
        config.setPoolName("ptmcp-pg-adapter-test");
        dataSource = new HikariDataSource(config);
        try (var connection = dataSource.getConnection();
                var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE pg_guard (id integer PRIMARY KEY, label text)");
            statement.execute("INSERT INTO pg_guard VALUES (1, 'a'), (2, 'b'), (3, 'c')");
        }
        adapter = new PgApprovedQueryAdapter(PgExecutorSettings.defaults(), CLOCK);
    }

    @AfterAll
    static void stopOwnedPostgres() {
        if (dataSource != null) {
            dataSource.close();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void approvedPlanExecutesWithinTheRowBudgetAndCallsCheckoutGuard() {
        AtomicBoolean checked = new AtomicBoolean();
        Plan plan = plan("SELECT id, label FROM public.pg_guard ORDER BY id", NOW, 2);

        PgReadOnlyResult result =
                adapter.execute(
                        plan,
                        dataSource,
                        7,
                        ignored -> checked.set(true),
                        new ReadOnlyCancellation());

        assertTrue(checked.get());
        assertEquals(2, result.rows().size());
        assertEquals(1, ((Number) result.rows().get(0).get(0)).intValue());
        assertEquals("a", result.rows().get(0).get(1));
        assertTrue(result.rowLimitReached());
        assertEquals(7, result.trace().poolGeneration());
    }

    @Test
    void expiredPlanIsRejectedBeforeTheCheckoutGuard() {
        AtomicBoolean checked = new AtomicBoolean();
        Plan plan =
                plan(
                        "SELECT id FROM public.pg_guard ORDER BY id",
                        NOW.minusSeconds(60),
                        1,
                        NOW.minusSeconds(30));

        DomainException failure =
                assertThrows(
                        DomainException.class,
                        () ->
                                adapter.execute(
                                        plan,
                                        dataSource,
                                        1,
                                        ignored -> checked.set(true),
                                        new ReadOnlyCancellation()));

        assertEquals(ErrorCode.RESOURCE_VERSION_CHANGED, failure.errorCode());
        assertFalse(checked.get());
    }

    @Test
    void tamperedFinalSqlIsRejectedBeforeTheCheckoutGuard() {
        AtomicBoolean checked = new AtomicBoolean();
        Plan plan =
                new Plan(
                        new ResourceId(1),
                        "SELECT id FROM public.pg_guard",
                        hash("SELECT label FROM public.pg_guard"),
                        1,
                        NOW.minusSeconds(1),
                        NOW.plusSeconds(30));

        DomainException failure =
                assertThrows(
                        DomainException.class,
                        () ->
                                adapter.execute(
                                        plan,
                                        dataSource,
                                        1,
                                        ignored -> checked.set(true),
                                        new ReadOnlyCancellation()));

        assertEquals(ErrorCode.INTERNAL_ERROR, failure.errorCode());
        assertFalse(checked.get());
    }

    @Test
    void checkoutGuardFailurePreventsExecution() {
        Plan plan = plan("SELECT id FROM public.pg_guard ORDER BY id", NOW, 1);

        DomainException failure =
                assertThrows(
                        DomainException.class,
                        () ->
                                adapter.execute(
                                        plan,
                                        dataSource,
                                        1,
                                        ignored -> {
                                            throw new DomainException(
                                                    ErrorCode.SECURITY_STATE_UNAVAILABLE);
                                        },
                                        new ReadOnlyCancellation()));

        assertEquals(ErrorCode.SECURITY_STATE_UNAVAILABLE, failure.errorCode());
    }

    private static Plan plan(String sql, Instant approvedAt, int maxRows) {
        return plan(sql, approvedAt, maxRows, approvedAt.plusSeconds(30));
    }

    private static Plan plan(String sql, Instant approvedAt, int maxRows, Instant expiresAt) {
        return new Plan(new ResourceId(1), sql, hash(sql), maxRows, approvedAt, expiresAt);
    }

    private static byte[] hash(String sql) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(sql.getBytes(StandardCharsets.UTF_8));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private record Plan(
            ResourceId datasourceId,
            String finalSql,
            byte[] sqlHash,
            int effectiveLimit,
            Instant approvedAt,
            Instant expiresAt)
            implements ApprovedSqlPlan {
        private Plan {
            sqlHash = sqlHash.clone();
        }

        @Override
        public byte[] sqlHash() {
            return sqlHash.clone();
        }
    }
}
