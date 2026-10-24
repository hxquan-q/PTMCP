package local.ptmcp.guard.approved;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.common.resource.DatabaseEngine;
import local.ptmcp.common.resource.ResourceId;
import local.ptmcp.common.resource.ResourceKey;
import local.ptmcp.common.resource.ResourceNamespace;
import local.ptmcp.common.resource.ResourceRef;
import local.ptmcp.guard.budget.BoundedParser;
import local.ptmcp.guard.scope.ColumnDef;
import local.ptmcp.guard.scope.RelationColumns;
import local.ptmcp.guard.scope.ScopeTableBinder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/** Order, short-circuit and closure guarantees of the guard pipeline. */
class GuardPipelineTest {
    private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");

    private BoundedParser parser;
    private RecordingCatalog catalog;
    private RecordingColumns columns;
    private RecordingDecider decider;
    private MutableClock clock;
    private GuardContext context;
    private GuardPolicy policy;

    @BeforeEach
    void setUp() {
        parser = new BoundedParser(BoundedParser.DEFAULT_TIMEOUT);
        catalog = new RecordingCatalog();
        columns = new RecordingColumns();
        decider = new RecordingDecider(true);
        clock = new MutableClock(NOW);
        context =
                new GuardContext(
                        9, 5, new ResourceId(7), 3, "shop", "", new ResourceId(11), 4, 1, 2, NOW);
        policy = GuardPolicy.mysql(false, false, 500);
    }

    @AfterEach
    void closeParser() {
        if (parser != null) {
            parser.close();
        }
    }

    @Test
    void happyPathProducesClosedApproval() {
        ApprovedQuery approved =
                GuardPipeline.guard("SELECT id, name FROM users", context, policy, deps());

        assertEquals(1, approved.resourceRefs().size());
        assertEquals(catalog.ref("users"), approved.resourceRefs().get(0));
        assertTrue(approved.finalSql().contains("shop.users"));
        assertTrue(approved.finalSql().toUpperCase().contains("LIMIT 500"));
        assertEquals(500, approved.effectiveLimit());
        assertArrayEquals(sha256(approved.finalSql()), approved.sqlHash());
        assertEquals(9, approved.principalId());
        assertEquals(5, approved.platformId());
        assertEquals(new ResourceId(7), approved.datasourceId());
        assertEquals(3, approved.configVersion());
        assertEquals("shop", approved.catalog());
        assertEquals("", approved.schema());
        assertEquals(new ResourceId(11), approved.snapshotId());
        assertEquals(4, approved.resourceVersion());
        assertEquals(1, approved.policyEpoch());
        assertEquals(2, approved.authVersion());
        assertEquals(GuardPolicy.CURRENT_GUARD_VERSION, approved.guardVersion());
        assertEquals(NOW, approved.approvedAt());
        assertEquals(NOW.plus(Duration.ofSeconds(10)), approved.expiresAt());
        assertTrue(approved.isUsable(NOW));
        assertTrue(approved.isUsable(NOW.plusSeconds(10)));
        assertFalse(approved.isUsable(NOW.plusSeconds(11)));
    }

    @Test
    void sameInputProducesIdenticalFinalSqlAndHash() {
        ApprovedQuery first = GuardPipeline.guard("SELECT id FROM users", context, policy, deps());
        ApprovedQuery second = GuardPipeline.guard("SELECT id FROM users", context, policy, deps());
        assertEquals(first.finalSql(), second.finalSql());
        assertArrayEquals(first.sqlHash(), second.sqlHash());
        assertEquals(first.resourceRefs(), second.resourceRefs());
        assertEquals(first.effectiveLimit(), second.effectiveLimit());
    }

    @Test
    void permissionDeniedStopsBeforeApprovalTextExists() {
        RecordingDecider denying = new RecordingDecider(false);
        GuardDependencies denyingDeps =
                new GuardDependencies(parser, catalog, columns, denying, clock);
        DomainException failure =
                assertThrows(
                        DomainException.class,
                        () ->
                                GuardPipeline.guard(
                                        "SELECT id FROM users", context, policy, denyingDeps));
        assertEquals(ErrorCode.TABLE_NOT_AUTHORIZED, failure.errorCode());
        assertEquals(1, denying.calls.get());
        assertEquals(1, catalog.lookups.get());
    }

    @Test
    void denialOfSecondRelationStillStopsAfterEachRefIsAsked() {
        catalog.put("users");
        catalog.put("orders");
        RecordingDecider selective =
                new RecordingDecider(true) {
                    @Override
                    public boolean canRead(GuardContext context, ResourceRef resource) {
                        super.canRead(context, resource);
                        return !resource.key().objectName().equals("orders");
                    }
                };
        GuardDependencies bothDeps =
                new GuardDependencies(parser, catalog, columns, selective, clock);
        DomainException failure =
                assertThrows(
                        DomainException.class,
                        () ->
                                GuardPipeline.guard(
                                        "SELECT u.id FROM users u JOIN orders o ON o.id = u.id",
                                        context,
                                        policy,
                                        bothDeps));
        assertEquals(ErrorCode.TABLE_NOT_AUTHORIZED, failure.errorCode());
        assertEquals(2, selective.calls.get());
    }

    @Test
    void lexicalRejectionNeverReachesParserOrAuthorizer() {
        DomainException failure =
                assertThrows(
                        DomainException.class,
                        () -> GuardPipeline.guard("SELECT 1; SELECT 2", context, policy, deps()));
        assertEquals(ErrorCode.SQL_NOT_READONLY, failure.errorCode());
        assertEquals(0, catalog.lookups.get());
        assertEquals(0, columns.lookups.get());
        assertEquals(0, decider.calls.get());
    }

    @Test
    void nonSelectIsRejectedByTheStatementGateBeforeBinding() {
        DomainException failure =
                assertThrows(
                        DomainException.class,
                        () ->
                                GuardPipeline.guard(
                                        "UPDATE users SET name = 'x'", context, policy, deps()));
        assertEquals(ErrorCode.SQL_NOT_READONLY, failure.errorCode());
        assertEquals(0, catalog.lookups.get());
        assertEquals(0, decider.calls.get());
    }

    @Test
    void unknownTableIsRejectedBeforeAuthorization() {
        DomainException failure =
                assertThrows(
                        DomainException.class,
                        () ->
                                GuardPipeline.guard(
                                        "SELECT id FROM missing", context, policy, deps()));
        assertEquals(ErrorCode.RESOURCE_NOT_FOUND, failure.errorCode());
        assertEquals(0, decider.calls.get());
    }

    @Test
    void relationBudgetIsEnforcedBeforeColumnBindingAndAuthorization() {
        GuardPolicy tight =
                new GuardPolicy(policy.lexicalPolicy(), 500, 0, GuardPolicy.CURRENT_GUARD_VERSION);
        DomainException failure =
                assertThrows(
                        DomainException.class,
                        () -> GuardPipeline.guard("SELECT id FROM users", context, tight, deps()));
        assertEquals(ErrorCode.SQL_BUDGET_EXCEEDED, failure.errorCode());
        assertEquals(0, columns.lookups.get());
        assertEquals(0, decider.calls.get());
    }

    @Test
    void outerLimitIsTightenedToTheMinimum() {
        ApprovedQuery original =
                GuardPipeline.guard(
                        "SELECT id FROM users LIMIT 100",
                        context,
                        GuardPolicy.mysql(false, false, 10),
                        deps());
        assertEquals(10, original.effectiveLimit());
        assertTrue(original.finalSql().toUpperCase().contains("LIMIT 10"));
        ApprovedQuery capped =
                GuardPipeline.guard("SELECT id FROM users LIMIT 1000", context, policy, deps());
        assertEquals(500, capped.effectiveLimit());
    }

    @Test
    void zeroOuterLimitIsRejectedNotApproved() {
        DomainException failure =
                assertThrows(
                        DomainException.class,
                        () ->
                                GuardPipeline.guard(
                                        "SELECT id FROM users LIMIT 0", context, policy, deps()));
        assertEquals(ErrorCode.SQL_UNSUPPORTED, failure.errorCode());
        // Authorization runs before rewrite, so the bound relation is asked once, then LIMIT 0 is
        // rejected as unsupported instead of being approved.
        assertEquals(1, decider.calls.get());
    }

    @Test
    void postgresqlHappyPathQualifiesSchemaAndKeepsLimit() {
        ResourceNamespace namespace =
                new ResourceNamespace(
                        new ResourceId(7), DatabaseEngine.POSTGRESQL, "shop", "sales");
        ResourceRef orders =
                new ResourceRef(new ResourceKey(namespace, "orders"), new ResourceId(1), 1, 1, 1);
        GuardDependencies pgDeps =
                new GuardDependencies(parser, key -> Optional.of(orders), columns, decider, clock);
        GuardContext pgContext =
                new GuardContext(
                        9,
                        5,
                        new ResourceId(7),
                        3,
                        "shop",
                        "sales",
                        new ResourceId(11),
                        4,
                        1,
                        2,
                        NOW);
        ApprovedQuery approved =
                GuardPipeline.guard(
                        "SELECT id FROM orders",
                        pgContext,
                        GuardPolicy.postgresql(true, 500),
                        pgDeps);
        assertTrue(approved.finalSql().contains("sales.orders"));
        assertEquals(500, approved.effectiveLimit());
    }

    private GuardDependencies deps() {
        return new GuardDependencies(parser, catalog, columns, decider, clock);
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static final class RecordingCatalog implements ScopeTableBinder.RelationCatalog {
        private final Map<String, ResourceRef> known = new HashMap<>();
        private final AtomicInteger lookups = new AtomicInteger();

        RecordingCatalog() {
            put("users");
        }

        void put(String objectName) {
            ResourceNamespace namespace =
                    new ResourceNamespace(new ResourceId(7), DatabaseEngine.MYSQL, "shop", "");
            known.put(
                    objectName,
                    new ResourceRef(
                            new ResourceKey(namespace, objectName),
                            new ResourceId(known.size() + 1L),
                            1,
                            1,
                            1));
        }

        ResourceRef ref(String objectName) {
            return known.get(objectName);
        }

        @Override
        public Optional<ResourceRef> find(ResourceKey key) {
            lookups.incrementAndGet();
            return Optional.ofNullable(known.get(key.objectName()));
        }
    }

    private static final class RecordingColumns implements RelationColumns {
        private final AtomicInteger lookups = new AtomicInteger();

        @Override
        public List<ColumnDef> columns(ResourceRef relation) {
            lookups.incrementAndGet();
            return List.of(
                    new ColumnDef(1, "id", "bigint", false),
                    new ColumnDef(2, "name", "varchar", true));
        }
    }

    private static class RecordingDecider implements PermissionDecider {
        private final AtomicInteger calls = new AtomicInteger();
        private volatile boolean allow;

        RecordingDecider(boolean allow) {
            this.allow = allow;
        }

        @Override
        public boolean canRead(GuardContext context, ResourceRef resource) {
            calls.incrementAndGet();
            return allow;
        }
    }

    private static final class MutableClock extends Clock {
        private volatile Instant instant;

        MutableClock(Instant start) {
            this.instant = start;
        }

        void advance(Duration amount) {
            instant = instant.plus(amount);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
