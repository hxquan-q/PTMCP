package local.ptmcp.common.authz;

import java.time.Instant;
import java.util.Objects;

/**
 * One already-scoped permission grant for a single resource, expanded by the caller from subject
 * inheritance and resource trees. Times are UTC and the window is half-open {@code [from, to)}.
 *
 * <p>The provider owns expansion (roles, departments, descendants) and platform scoping; this value
 * only carries the effect that applies to the named resource and action. A grant is not proof of
 * authorization by itself: evaluate it with {@link PermissionEvaluator}.
 */
public record PermissionGrant(
        long grantId, Effect effect, Action action, Instant validFrom, Instant validTo) {
    public PermissionGrant {
        if (grantId <= 0) {
            throw new IllegalArgumentException("grantId must be positive");
        }
        Objects.requireNonNull(effect, "effect");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(validFrom, "validFrom");
        if (validTo != null && !validTo.isAfter(validFrom)) {
            throw new IllegalArgumentException("validTo must be after validFrom");
        }
    }

    public enum Effect {
        ALLOW,
        DENY
    }

    public enum Action {
        META,
        READ
    }

    /** {@code [from, to)} containment; a null end is open. */
    public boolean covers(Instant at) {
        return !at.isBefore(validFrom) && (validTo == null || at.isBefore(validTo));
    }
}
