package local.ptmcp.common.authz;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Expands stored ACL rows for one subject against one target resource.
 *
 * <p>A grant applies only when its subject is part of the effective subject scope and its resource
 * equals the target or, with {@code includeDescendants}, is an ancestor of the target. Expansion is
 * strictly downwards: a grant on a child never covers a parent, and unrelated branches are
 * discarded. Rows are deduplicated by {@code grantId}; time windows are copied unchanged so the
 * evaluator remains the single place that decides validity.
 *
 * <p>Platform scope is deliberately not applied here. Callers must still intersect the evaluated
 * result with the platform META/READ ceiling.
 */
public final class GrantExpander {
    private GrantExpander() {}

    public static List<PermissionGrant> expand(
            List<GrantRow> grants,
            SubjectScope subject,
            Collection<ResourceNode> resourceTree,
            long targetResourceId) {
        Objects.requireNonNull(grants, "grants");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(resourceTree, "resourceTree");
        SubjectScope.requirePositive(targetResourceId, "targetResourceId");

        Map<Long, ResourceNode> tree = validateTree(resourceTree);
        if (!tree.containsKey(targetResourceId)) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        Set<Long> ancestorChain = ancestorChain(targetResourceId, tree);

        Map<Long, PermissionGrant> expanded = new LinkedHashMap<>();
        for (GrantRow grant : grants) {
            if (!matchesSubject(grant, subject)) {
                continue;
            }
            boolean coversTarget =
                    grant.resourceId() == targetResourceId
                            || (grant.includeDescendants()
                                    && ancestorChain.contains(grant.resourceId()));
            if (!coversTarget) {
                continue;
            }
            PermissionGrant permission =
                    new PermissionGrant(
                            grant.grantId(),
                            grant.effect(),
                            grant.action(),
                            grant.validFrom(),
                            grant.validTo());
            expanded.putIfAbsent(grant.grantId(), permission);
        }
        return List.copyOf(expanded.values());
    }

    private static boolean matchesSubject(GrantRow grant, SubjectScope subject) {
        return switch (grant.subjectType()) {
            case USER -> grant.subjectId() == subject.userId();
            case ROLE -> subject.enabledRoleIds().contains(grant.subjectId());
            case DEPT ->
                    (subject.deptId() != null && grant.subjectId() == subject.deptId())
                            || subject.deptAncestorIds().contains(grant.subjectId());
        };
    }

    private static Map<Long, ResourceNode> validateTree(Collection<ResourceNode> resourceTree) {
        Map<Long, ResourceNode> tree = new HashMap<>();
        for (ResourceNode node : resourceTree) {
            if (node == null || tree.putIfAbsent(node.id(), node) != null) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
        }
        for (ResourceNode node : tree.values()) {
            if (node.parentId() != null && !tree.containsKey(node.parentId())) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
        }
        for (ResourceNode node : tree.values()) {
            Set<Long> seen = new HashSet<>();
            ResourceNode cursor = node;
            while (cursor != null) {
                if (!seen.add(cursor.id())) {
                    throw new DomainException(ErrorCode.INVALID_ARGUMENT);
                }
                Long parentId = cursor.parentId();
                cursor = parentId == null ? null : tree.get(parentId);
            }
        }
        return tree;
    }

    private static Set<Long> ancestorChain(long targetResourceId, Map<Long, ResourceNode> tree) {
        Set<Long> chain = new LinkedHashSet<>();
        ResourceNode cursor = tree.get(targetResourceId);
        while (cursor != null && chain.add(cursor.id())) {
            Long parentId = cursor.parentId();
            cursor = parentId == null ? null : tree.get(parentId);
        }
        if (cursor != null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        return chain;
    }
}
