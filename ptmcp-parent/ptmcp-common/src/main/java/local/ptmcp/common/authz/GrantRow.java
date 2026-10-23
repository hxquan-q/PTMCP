package local.ptmcp.common.authz;

import java.time.Instant;
import java.util.Objects;

/**
 * One stored ACL row before subject and descendant expansion. Time validity is preserved exactly
 * and is not applied here; {@link PermissionEvaluator} owns the {@code [from, to)} decision.
 */
public record GrantRow(
        SubjectType subjectType,
        long subjectId,
        long resourceId,
        PermissionGrant.Action action,
        PermissionGrant.Effect effect,
        boolean includeDescendants,
        Instant validFrom,
        Instant validTo,
        long grantId) {
    public GrantRow {
        Objects.requireNonNull(subjectType, "subjectType");
        SubjectScope.requirePositive(subjectId, "subjectId");
        SubjectScope.requirePositive(resourceId, "resourceId");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(effect, "effect");
        Objects.requireNonNull(validFrom, "validFrom");
        if (validTo != null && !validTo.isAfter(validFrom)) {
            throw new IllegalArgumentException("validTo must be after validFrom");
        }
        SubjectScope.requirePositive(grantId, "grantId");
    }
}
