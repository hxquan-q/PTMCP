package local.ptmcp.regression;

import local.ptmcp.common.resource.DatabaseEngine;
import local.ptmcp.common.resource.ResourceId;
import local.ptmcp.common.resource.ResourceKey;
import local.ptmcp.common.resource.ResourceNamespace;
import local.ptmcp.common.resource.ResourceRef;
import local.ptmcp.guard.approved.ApprovedQuery;
import local.ptmcp.guard.approved.GuardContext;
import local.ptmcp.guard.approved.GuardDependencies;
import local.ptmcp.guard.approved.GuardPipeline;
import local.ptmcp.guard.approved.GuardPolicy;
import local.ptmcp.guard.approved.PermissionDecider;
import local.ptmcp.guard.budget.BoundedParser;
import local.ptmcp.guard.scope.ColumnDef;
import local.ptmcp.guard.scope.RelationColumns;
import local.ptmcp.guard.scope.ScopeTableBinder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared fixtures for the synthetic regression suite. Pure JVM, no Docker. */
public final class RegressionHarness {
    public static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
    public static final String CATALOG = "shop";

    private final BoundedParser parser;
    private final HarnessCatalog catalog;
    private final HarnessColumns columns;
    private final HarnessDecider decider;
    private final GuardContext context;
    private final GuardPolicy policy;

    public RegressionHarness() {
        this.parser = new BoundedParser(BoundedParser.DEFAULT_TIMEOUT);
        this.catalog = new HarnessCatalog();
        this.columns = new HarnessColumns();
        this.decider = new HarnessDecider();
        this.context =
                new GuardContext(
                        9, 5, new ResourceId(7), 3, CATALOG, "", new ResourceId(11), 4, 1, 2, NOW);
        this.policy = GuardPolicy.mysql(false, false, 500);
    }

    public ApprovedQuery guard(String sql) {
        return GuardPipeline.guard(sql, context, policy, deps());
    }

    public void addTable(String name) {
        catalog.put(name);
    }

    public GuardDependencies deps() {
        return new GuardDependencies(
                parser, catalog, columns, decider, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    static final class HarnessCatalog implements ScopeTableBinder.RelationCatalog {
        private final Map<String, ResourceRef> known = new HashMap<>();
        final AtomicInteger lookups = new AtomicInteger();

        HarnessCatalog() {
            put("users");
            put("orders");
            put("audit_log");
            put("sys_config");
        }

        void put(String objectName) {
            ResourceNamespace namespace =
                    new ResourceNamespace(new ResourceId(7), DatabaseEngine.MYSQL, CATALOG, "");
            known.put(
                    objectName,
                    new ResourceRef(
                            new ResourceKey(namespace, objectName),
                            new ResourceId(known.size() + 100L),
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

    static final class HarnessColumns implements RelationColumns {
        final AtomicInteger lookups = new AtomicInteger();

        @Override
        public List<ColumnDef> columns(ResourceRef relation) {
            lookups.incrementAndGet();
            return List.of(
                    new ColumnDef(1, "id", "bigint", false),
                    new ColumnDef(2, "name", "varchar", true),
                    new ColumnDef(3, "value", "decimal", true),
                    new ColumnDef(4, "created_at", "timestamp", true));
        }
    }

    static final class HarnessDecider implements PermissionDecider {
        private final AtomicInteger calls = new AtomicInteger();
        private volatile boolean allow = true;

        @Override
        public boolean canRead(GuardContext context, ResourceRef resource) {
            calls.incrementAndGet();
            return allow;
        }
    }
}
