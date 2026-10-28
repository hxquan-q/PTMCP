package local.ptmcp.datasource;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.common.execution.ApprovedSqlPlan;
import local.ptmcp.common.resource.DatabaseEngine;
import local.ptmcp.common.resource.ResourceId;
import local.ptmcp.datasource.postgres.PgApprovedQueryAdapter;
import local.ptmcp.datasource.postgres.PgExecutorSettings;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

class ApprovedQueryCheckoutTest {
    private static final Instant NOW = Instant.parse("2026-10-27T00:00:00Z");

    @Test
    void mysqlCannotBorrowADifferentConfigurationThanTheApprovedPlan() throws Exception {
        AtomicInteger checkouts = new AtomicInteger();
        try (DataSourcePoolRegistry registry = registry(checkouts, 2)) {
            ApprovedQueryAdapter adapter =
                    new ApprovedQueryAdapter(
                            new MysqlReadOnlyExecutor(registry), Clock.fixed(NOW, ZoneOffset.UTC));
            DomainException failure =
                    assertThrows(
                            DomainException.class,
                            () ->
                                    adapter.execute(
                                            plan(), ignored -> {}, new ReadOnlyCancellation()));
            assertAll(
                    () -> assertEquals(ErrorCode.RESOURCE_VERSION_CHANGED, failure.errorCode()),
                    () -> assertEquals(0, checkouts.get()));
        }
    }

    @Test
    void mysqlRechecksApprovalExpiryAfterSlowAuthoritativeRevalidation() throws Exception {
        AtomicInteger checkouts = new AtomicInteger();
        MutableClock clock = new MutableClock();
        try (DataSourcePoolRegistry registry = registry(checkouts, 1)) {
            ApprovedQueryAdapter adapter =
                    new ApprovedQueryAdapter(new MysqlReadOnlyExecutor(registry), clock);
            DomainException failure =
                    assertThrows(
                            DomainException.class,
                            () ->
                                    adapter.execute(
                                            plan(),
                                            ignored -> clock.advance(),
                                            new ReadOnlyCancellation()));
            assertAll(
                    () -> assertEquals(ErrorCode.RESOURCE_VERSION_CHANGED, failure.errorCode()),
                    () -> assertEquals(0, checkouts.get()));
        }
    }

    @Test
    void postgresCannotBorrowADifferentConfigurationThanTheApprovedPlan() throws Exception {
        AtomicInteger checkouts = new AtomicInteger();
        try (DataSourcePoolRegistry registry =
                new DataSourcePoolRegistry((key, secret, limits) -> pool(checkouts))) {
            PoolHandle handle =
                    registry.activate(
                            new PoolKey(
                                    1,
                                    2,
                                    DatabaseEngine.POSTGRESQL,
                                    "synthetic.invalid",
                                    5432,
                                    "synthetic",
                                    "reader",
                                    2),
                            new char[] {'x'},
                            PoolLimits.of(1));
            PgApprovedQueryAdapter adapter =
                    new PgApprovedQueryAdapter(
                            PgExecutorSettings.defaults(), Clock.fixed(NOW, ZoneOffset.UTC));
            DomainException failure =
                    assertThrows(
                            DomainException.class,
                            () ->
                                    adapter.execute(
                                            plan(),
                                            handle,
                                            ignored -> {},
                                            new ReadOnlyCancellation()));
            assertAll(
                    () -> assertEquals(ErrorCode.RESOURCE_VERSION_CHANGED, failure.errorCode()),
                    () -> assertEquals(0, checkouts.get()));
        }
    }

    @Test
    void postgresRechecksApprovalExpiryAfterSlowAuthoritativeRevalidation() throws Exception {
        AtomicInteger checkouts = new AtomicInteger();
        MutableClock clock = new MutableClock();
        PgApprovedQueryAdapter adapter =
                new PgApprovedQueryAdapter(PgExecutorSettings.defaults(), clock);
        PoolHandle handle =
                new PoolHandle(
                        new PoolKey(
                                1,
                                1,
                                DatabaseEngine.POSTGRESQL,
                                "synthetic.invalid",
                                5432,
                                "synthetic",
                                "reader",
                                1),
                        1,
                        pool(checkouts));
        DomainException failure =
                assertThrows(
                        DomainException.class,
                        () ->
                                adapter.execute(
                                        plan(),
                                        handle,
                                        ignored -> clock.advance(),
                                        new ReadOnlyCancellation()));
        assertAll(
                () -> assertEquals(ErrorCode.RESOURCE_VERSION_CHANGED, failure.errorCode()),
                () -> assertEquals(0, checkouts.get()));
    }

    private static DataSourcePoolRegistry registry(AtomicInteger checkouts, long configVersion) {
        DataSourcePoolRegistry registry =
                new DataSourcePoolRegistry((key, secret, limits) -> pool(checkouts));
        registry.activate(
                new PoolKey(
                        1,
                        configVersion,
                        DatabaseEngine.MYSQL,
                        "synthetic.invalid",
                        3306,
                        "synthetic",
                        "reader",
                        configVersion),
                new char[] {'x'},
                PoolLimits.of(1));
        return registry;
    }

    private static DataSource pool(AtomicInteger checkouts) {
        return (DataSource)
                Proxy.newProxyInstance(
                        ApprovedQueryCheckoutTest.class.getClassLoader(),
                        new Class<?>[] {DataSource.class},
                        (proxy, method, args) -> {
                            if ("getConnection".equals(method.getName())) {
                                checkouts.incrementAndGet();
                                throw new SQLException("Unexpected business checkout");
                            }
                            throw new UnsupportedOperationException(method.getName());
                        });
    }

    private static Plan plan() throws Exception {
        String sql = "SELECT 1 AS id";
        return new Plan(
                new ResourceId(1),
                1,
                sql,
                MessageDigest.getInstance("SHA-256").digest(sql.getBytes(StandardCharsets.UTF_8)),
                1,
                NOW,
                NOW.plusSeconds(10));
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

    private static final class MutableClock extends Clock {
        private Instant now = NOW;

        void advance() {
            now = NOW.plusSeconds(30);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
