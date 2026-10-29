package local.ptmcp.gateway.mcp;

import local.ptmcp.common.authz.GrantExpander;
import local.ptmcp.common.authz.GrantRow;
import local.ptmcp.common.authz.PermissionEvaluator;
import local.ptmcp.common.authz.PermissionGrant;
import local.ptmcp.common.authz.ResourceNode;
import local.ptmcp.common.authz.SubjectScope;
import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.org.AuthorizationReader;
import local.ptmcp.persistence.schema.PublishedSnapshotReader;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Evaluates the effective META/READ decision for snapshot objects of one datasource: the user's
 * expanded grants intersected with the platform scope ceiling, with DENY precedence and half-open
 * time windows as defined by the shared pure evaluators. The tree covers datasource → namespace →
 * object identity rows; a grant or platform row only covers a target when its stored generation
 * still matches the live row, so a rebuilt object never inherits stale authorization. Node ids in
 * the tree are dense synthetic ids because datasource, namespace and object rows come from
 * independent sequences and their raw ids may collide numerically.
 */
final class ToolPermissionService {
    private final AuthorizationReader authorization;
    private final boolean departmentInheritance;

    ToolPermissionService(AuthorizationReader authorization, boolean departmentInheritance) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.departmentInheritance = departmentInheritance;
    }

    /** One evaluation session: loaded once per tool call, decisions memoized per object. */
    final class Session {
        private final Instant now;
        private final SubjectScope scope;
        private final List<AuthorizationReader.AclGrant> grants;
        private final List<AuthorizationReader.PlatformScopeRow> platformScope;
        private final Forest forest;
        private final Map<Long, PermissionEvaluator.Decision> cache = new HashMap<>();

        private Session(
                Instant now,
                SubjectScope scope,
                List<AuthorizationReader.AclGrant> grants,
                List<AuthorizationReader.PlatformScopeRow> platformScope,
                Forest forest) {
            this.now = now;
            this.scope = scope;
            this.grants = grants;
            this.platformScope = platformScope;
            this.forest = forest;
        }

        PermissionEvaluator.Decision decide(long objectId) {
            return cache.computeIfAbsent(objectId, this::evaluate);
        }

        /** Rechecks half-open grant windows after slow metadata work, without cached decisions. */
        Session atCurrentTime() {
            return new Session(Instant.now(), scope, grants, platformScope, forest);
        }

        /** The platform's META/READ ceiling for the object, before any user grants. */
        boolean platformVisible(long objectId) {
            Forest.Node target = forest.object(objectId);
            return platformCovers(target, false);
        }

        /** True when a currently valid META-action DENY covers the object; hides it in DISCOVER. */
        boolean explicitMetaDeny(long objectId) {
            for (AuthorizationReader.AclGrant grant : matchingGrants(objectId)) {
                if (grant.effect() == PermissionGrant.Effect.DENY
                        && grant.action() == PermissionGrant.Action.META
                        && !now.isBefore(grant.validFrom())
                        && (grant.validTo() == null || now.isBefore(grant.validTo()))) {
                    return true;
                }
            }
            return false;
        }

        private PermissionEvaluator.Decision evaluate(long objectId) {
            Forest.Node target = forest.object(objectId);
            List<GrantRow> grantRows = new ArrayList<>();
            for (AuthorizationReader.AclGrant grant : matchingGrants(objectId)) {
                grantRows.add(forest.toGrantRow(grant));
            }
            List<PermissionGrant> expanded =
                    GrantExpander.expand(
                            grantRows, scope, forest.resourceNodes(), target.syntheticId());
            boolean platformMeta = platformCovers(target, false);
            boolean platformRead = platformCovers(target, true);
            return PermissionEvaluator.evaluate(expanded, platformMeta, platformRead, now);
        }

        private List<AuthorizationReader.AclGrant> matchingGrants(long objectId) {
            Forest.Node target = forest.object(objectId);
            List<Forest.Node> chain = forest.chain(target);
            List<AuthorizationReader.AclGrant> matching = new ArrayList<>();
            for (AuthorizationReader.AclGrant grant : grants) {
                if (forest.matchesChain(
                        grant.resourceType(),
                        grant.resourceId(),
                        grant.resourceGeneration(),
                        chain)) {
                    matching.add(grant);
                }
            }
            return matching;
        }

        /**
         * Platform ceiling for one target. A READ row also implies META; a NAMESPACE or DATASOURCE
         * row only reaches objects when descendants are included.
         */
        private boolean platformCovers(Forest.Node target, boolean forRead) {
            for (AuthorizationReader.PlatformScopeRow row : platformScope) {
                boolean actionApplies =
                        forRead
                                ? "READ".equals(row.action())
                                : "META".equals(row.action()) || "READ".equals(row.action());
                if (!actionApplies) {
                    continue;
                }
                for (Forest.Node node : forest.chain(target)) {
                    if (!forest.matchesNode(
                            row.resourceType(), row.resourceId(), row.generation(), node)) {
                        continue;
                    }
                    if (node.type == Kind.OBJECT || row.descendants()) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private enum Kind {
        DATASOURCE("DATASOURCE"),
        NAMESPACE("NAMESPACE"),
        OBJECT("OBJECT");

        final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    private static final class Forest {
        private record Node(
                Kind type, long rawId, long parentId, long generation, long syntheticId) {}

        private final Map<String, Node> byKey = new LinkedHashMap<>();
        private final Map<Long, Node> objectsByRawId = new LinkedHashMap<>();
        private long nextSyntheticId;

        void addDatasource(long rawId, long configVersion) {
            put(new Node(Kind.DATASOURCE, rawId, 0, configVersion, ++nextSyntheticId));
        }

        void addNamespace(long rawId, long parentRawId, long generation) {
            put(new Node(Kind.NAMESPACE, rawId, parentRawId, generation, ++nextSyntheticId));
        }

        void addObject(long rawId, long parentRawId, long generation) {
            Node node = new Node(Kind.OBJECT, rawId, parentRawId, generation, ++nextSyntheticId);
            put(node);
            objectsByRawId.put(rawId, node);
        }

        private void put(Node node) {
            byKey.put(node.type() + ":" + node.rawId(), node);
        }

        Node object(long rawId) {
            Node node = objectsByRawId.get(rawId);
            if (node == null) {
                throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
            }
            return node;
        }

        /** The synthetic-id view the shared expander works on; parent links stay intact. */
        List<ResourceNode> resourceNodes() {
            List<ResourceNode> tree = new ArrayList<>(byKey.size());
            for (Node node : byKey.values()) {
                Node parent = parent(node);
                tree.add(
                        new ResourceNode(
                                node.syntheticId(), parent == null ? null : parent.syntheticId()));
            }
            return tree;
        }

        GrantRow toGrantRow(AuthorizationReader.AclGrant grant) {
            Node target = byKey.get(grant.resourceType() + ":" + grant.resourceId());
            if (target == null) {
                throw new DomainException(ErrorCode.INTERNAL_ERROR);
            }
            return new GrantRow(
                    grant.subjectType(),
                    grant.subjectId(),
                    target.syntheticId(),
                    grant.action(),
                    grant.effect(),
                    grant.includeDescendants(),
                    grant.validFrom(),
                    grant.validTo(),
                    grant.grantId());
        }

        boolean matchesChain(
                String resourceType, long resourceId, long generation, List<Node> chain) {
            for (Node node : chain) {
                if (matchesNode(resourceType, resourceId, generation, node)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * A stored row matches a live node when the type and raw id agree and, for namespace and
         * object rows, the stored generation is still the live one; DATASOURCE rows keep the
         * nominal-generation convention of the admin writer.
         */
        boolean matchesNode(String resourceType, long resourceId, long generation, Node node) {
            if (!node.type().label.equals(resourceType) || node.rawId() != resourceId) {
                return false;
            }
            return node.type() == Kind.DATASOURCE || node.generation() == generation;
        }

        List<Node> chain(Node target) {
            List<Node> chain = new ArrayList<>(3);
            Node cursor = target;
            while (cursor != null && chain.size() < 8) {
                chain.add(cursor);
                cursor = parent(cursor);
            }
            return chain;
        }

        private Node parent(Node node) {
            if (node.type() == Kind.DATASOURCE) {
                return null;
            }
            Kind parentKind = node.type() == Kind.OBJECT ? Kind.NAMESPACE : Kind.DATASOURCE;
            return byKey.get(parentKind + ":" + node.parentId());
        }
    }

    Session open(
            long userId,
            long platformId,
            long datasourceId,
            PublishedSnapshotReader.Published published) {
        Instant now = Instant.now();
        AuthorizationReader.SubjectScopeData subject =
                authorization.loadSubjectScope(userId, departmentInheritance);
        List<AuthorizationReader.AclGrant> grants = authorization.loadGrants(userId, subject);
        List<AuthorizationReader.PlatformScopeRow> platformScope =
                authorization.loadPlatformScope(platformId);
        Forest forest = new Forest();
        forest.addDatasource(datasourceId, published.configVersion());
        for (PublishedSnapshotReader.Namespace namespace : published.namespaces()) {
            forest.addNamespace(namespace.id(), datasourceId, namespace.generation());
            for (PublishedSnapshotReader.TableObject object : namespace.objects()) {
                forest.addObject(object.id(), namespace.id(), object.generation());
            }
        }
        return new Session(
                now,
                new SubjectScope(
                        userId, subject.roleIds(), subject.deptId(), subject.deptAncestorIds()),
                grants,
                platformScope,
                forest);
    }
}
