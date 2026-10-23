package local.ptmcp.common.authz;

import java.util.Objects;
import java.util.Set;

/**
 * The already-effective subjects a user acts through at one instant.
 *
 * <p>Callers must only include roles with {@code status=0,del_flag=0}; disabled roles contribute
 * nothing. Department ancestors are included only when the deployment enables department
 * inheritance, and each ancestor must itself be enabled. This value is not a permission decision.
 */
public record SubjectScope(
        long userId, Set<Long> enabledRoleIds, Long deptId, Set<Long> deptAncestorIds) {
    public SubjectScope {
        requirePositive(userId, "userId");
        enabledRoleIds = Set.copyOf(Objects.requireNonNull(enabledRoleIds, "enabledRoleIds"));
        enabledRoleIds.forEach(roleId -> requirePositive(roleId, "roleId"));
        if (deptId != null) {
            requirePositive(deptId, "deptId");
        }
        deptAncestorIds = Set.copyOf(Objects.requireNonNull(deptAncestorIds, "deptAncestorIds"));
        deptAncestorIds.forEach(dept -> requirePositive(dept, "deptAncestorId"));
    }

    static void requirePositive(long value, String label) {
        if (value <= 0) {
            throw new IllegalArgumentException(label + " must be positive");
        }
    }
}
