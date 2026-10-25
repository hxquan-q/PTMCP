package local.ptmcp.persistence.org;

import local.ptmcp.common.authz.GrantExpander;
import local.ptmcp.common.authz.GrantRow;
import local.ptmcp.common.authz.PermissionEvaluator;
import local.ptmcp.common.authz.PermissionGrant;
import local.ptmcp.common.authz.ResourceNode;
import local.ptmcp.common.authz.SubjectScope;
import local.ptmcp.common.authz.SubjectType;
import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.sql.DataSource;

/**
 * Read-side assembly for authorization decisions: builds the {@link SubjectScope} of a user from
 * the org tables, projects ACTIVE {@code acl_grant} rows into frozen {@link GrantRow} values with
 * cross-type encoded resource ids, materializes the ACTIVE resource tree, computes the platform
 * ceiling, and answers preview questions. Purely read-only — a preview never touches a business
 * database.
 */
public final class AclDirectory {
    private final DataSource primary;

    public AclDirectory(DataSource primary) {
        this.primary = Objects.requireNonNull(primary, "primary");
    }

    /** One resource's intersected decision for preview purposes. */
    public record ResourceDecision(
            long resourceId,
            boolean meta,
            boolean read,
            List<Long> sourceGrantIds,
            Instant nextBoundaryAt) {
        public ResourceDecision {
            sourceGrantIds = List.copyOf(sourceGrantIds);
        }
    }

    public SubjectScope subjectScope(long userId) {
        try (Connection connection = OrgSql.configure(primary)) {
            Long deptId;
            try (PreparedStatement statement =
                    OrgSql.statement(
                            connection,
                            "SELECT dept_id FROM sys_user_profile WHERE user_id = ?"
                                    + " AND status = 'ACTIVE'")) {
                statement.setLong(1, userId);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
                    }
                    deptId = result.getLong(1);
                }
            }
            Set<Long> deptAncestors = new HashSet<>();
            try (PreparedStatement statement =
                    OrgSql.statement(
                            connection,
                            "SELECT ancestors FROM sys_dept WHERE dept_id = ?"
                                    + " AND del_flag = '0'")) {
                statement.setLong(1, deptId);
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        for (String token : result.getString(1).split(",")) {
                            if (!token.isBlank() && !"0".equals(token)) {
                                deptAncestors.add(Long.parseLong(token.trim()));
                            }
                        }
                    }
                }
            }
            Set<Long> roleIds = new HashSet<>();
            try (PreparedStatement statement =
                    OrgSql.statement(
                            connection,
                            "SELECT r.role_id FROM sys_user_role ur"
                                    + " JOIN sys_role r ON r.role_id = ur.role_id"
                                    + " WHERE ur.user_id = ? AND r.status = '0' AND r.del_flag = '0'")) {
                statement.setLong(1, userId);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        roleIds.add(result.getLong(1));
                    }
                }
            }
            return new SubjectScope(userId, Set.copyOf(roleIds), deptId, Set.copyOf(deptAncestors));
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    public List<GrantRow> activeGrants(long userId) {
        SubjectScope scope = subjectScope(userId);
        try (Connection connection = OrgSql.configure(primary);
                PreparedStatement statement =
                        OrgSql.statement(
                                connection,
                                "SELECT id, subject_type, subject_id, resource_type, resource_id,"
                                        + " action, effect, include_descendants, valid_from, valid_to"
                                        + " FROM acl_grant WHERE status = 'ACTIVE'"
                                        + " AND resource_generation = CASE resource_type"
                                        + " WHEN 'DATASOURCE' THEN COALESCE((SELECT config_version"
                                        + "   FROM datasource WHERE id = resource_id"
                                        + "   AND status = 'ACTIVE'), -1)"
                                        + " WHEN 'NAMESPACE' THEN COALESCE((SELECT generation"
                                        + "   FROM resource_namespace WHERE id = resource_id"
                                        + "   AND status = 'ACTIVE'), -1)"
                                        + " ELSE COALESCE((SELECT generation FROM resource_object"
                                        + "   WHERE id = resource_id AND status = 'ACTIVE'), -1)"
                                        + " END ORDER BY id");
                ResultSet result = statement.executeQuery()) {
            List<GrantRow> grants = new ArrayList<>();
            while (result.next()) {
                long subjectId = result.getLong(3);
                boolean relevant =
                        switch (result.getString(2)) {
                            case "USER" -> subjectId == userId;
                            case "ROLE" -> scope.enabledRoleIds().contains(subjectId);
                            case "DEPT" ->
                                    subjectId == scope.deptId()
                                            || scope.deptAncestorIds().contains(subjectId);
                            default -> false;
                        };
                if (!relevant) {
                    continue;
                }
                grants.add(
                        new GrantRow(
                                SubjectType.valueOf(result.getString(2)),
                                subjectId,
                                ResourceIds.encode(
                                        ResourceIds.Type.valueOf(result.getString(4)),
                                        result.getLong(5)),
                                PermissionGrant.Action.valueOf(result.getString(6)),
                                PermissionGrant.Effect.valueOf(result.getString(7)),
                                result.getInt(8) == 1,
                                OrgSql.toInstant(
                                        result.getObject(9, java.time.LocalDateTime.class)),
                                OrgSql.toInstant(
                                        result.getObject(10, java.time.LocalDateTime.class)),
                                result.getLong(1)));
            }
            return grants;
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    public List<ResourceNode> resourceTree() {
        try (Connection connection = OrgSql.configure(primary)) {
            List<ResourceNode> nodes = new ArrayList<>();
            appendDatasources(connection, nodes);
            appendNamespaces(connection, nodes);
            appendObjects(connection, nodes);
            return nodes;
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    /**
     * Intersects user grants with the platform ceiling per requested resource. Unknown ids are
     * denied closed rather than rejected: a preview only judges, it never fetches business data.
     */
    public List<ResourceDecision> preview(
            long subjectUserId, long platformId, List<Long> resourceIds, Instant at) {
        if (resourceIds == null
                || resourceIds.isEmpty()
                || resourceIds.size() > 100
                || resourceIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        SubjectScope scope = subjectScope(subjectUserId);
        List<GrantRow> grants = activeGrants(subjectUserId);
        List<ResourceNode> tree = resourceTree();
        Map<Long, ResourceNode> nodes = new HashMap<>();
        for (ResourceNode node : tree) {
            nodes.put(node.id(), node);
        }
        List<ResourceDecision> decisions = new ArrayList<>();
        for (Long resourceId : resourceIds) {
            if (!nodes.containsKey(resourceId)) {
                // Unknown ids deny closed; they are judged, not rejected.
                decisions.add(new ResourceDecision(resourceId, false, false, List.of(), null));
                continue;
            }
            int[] ceiling = platformCeiling(platformId, resourceId, nodes);
            boolean platformMeta = ceiling[0] > 0;
            boolean platformRead = ceiling[1] > 0;
            PermissionEvaluator.Decision decision =
                    PermissionEvaluator.evaluate(
                            GrantExpander.expand(grants, scope, tree, resourceId),
                            platformMeta,
                            platformRead,
                            at);
            decisions.add(
                    new ResourceDecision(
                            resourceId,
                            decision.meta(),
                            decision.read(),
                            decision.allowGrantIds(),
                            decision.nextBoundaryAt().orElse(null)));
        }
        return decisions;
    }

    /** {meta, read} ceiling flags for one target, computed over the whole platform scope. */
    public int[] platformCeiling(
            long platformId, long targetResourceId, Map<Long, ResourceNode> nodes) {
        Set<Long> ancestorChain = new HashSet<>();
        ResourceNode cursor = nodes.get(targetResourceId);
        while (cursor != null && cursor.parentId() != null) {
            ancestorChain.add(cursor.parentId());
            cursor = nodes.get(cursor.parentId());
        }
        int[] ceiling = new int[2];
        try (Connection connection = OrgSql.configure(primary);
                PreparedStatement statement =
                        OrgSql.statement(
                                connection,
                                "SELECT resource_type, resource_id, action, include_descendants"
                                        + " FROM platform_scope WHERE platform_id = ?")) {
            statement.setLong(1, platformId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    long scopeResource =
                            ResourceIds.encode(
                                    ResourceIds.Type.valueOf(result.getString(1)),
                                    result.getLong(2));
                    boolean covers =
                            scopeResource == targetResourceId
                                    || (result.getInt(4) == 1
                                            && ancestorChain.contains(scopeResource));
                    if (!covers) {
                        continue;
                    }
                    if ("META".equals(result.getString(3))) {
                        ceiling[0] = 1;
                    } else {
                        ceiling[1] = 1;
                    }
                }
            }
            return ceiling;
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private static void appendDatasources(Connection connection, List<ResourceNode> nodes)
            throws SQLException {
        try (PreparedStatement statement =
                        OrgSql.statement(
                                connection,
                                "SELECT id FROM datasource WHERE status = 'ACTIVE' ORDER BY id");
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                nodes.add(
                        new ResourceNode(
                                ResourceIds.encode(ResourceIds.Type.DATASOURCE, result.getLong(1)),
                                null));
            }
        }
    }

    private static void appendNamespaces(Connection connection, List<ResourceNode> nodes)
            throws SQLException {
        try (PreparedStatement statement =
                        OrgSql.statement(
                                connection,
                                "SELECT id, datasource_id FROM resource_namespace WHERE status = 'ACTIVE'"
                                        + " ORDER BY id");
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                nodes.add(
                        new ResourceNode(
                                ResourceIds.encode(ResourceIds.Type.NAMESPACE, result.getLong(1)),
                                ResourceIds.encode(
                                        ResourceIds.Type.DATASOURCE, result.getLong(2))));
            }
        }
    }

    private static void appendObjects(Connection connection, List<ResourceNode> nodes)
            throws SQLException {
        try (PreparedStatement statement =
                        OrgSql.statement(
                                connection,
                                "SELECT id, namespace_id FROM resource_object WHERE status = 'ACTIVE'"
                                        + " ORDER BY id");
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                nodes.add(
                        new ResourceNode(
                                ResourceIds.encode(ResourceIds.Type.OBJECT, result.getLong(1)),
                                ResourceIds.encode(ResourceIds.Type.NAMESPACE, result.getLong(2))));
            }
        }
    }
}
