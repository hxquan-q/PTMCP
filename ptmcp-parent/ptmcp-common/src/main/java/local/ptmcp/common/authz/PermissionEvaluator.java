package local.ptmcp.common.authz;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Evaluates expanded grants for one resource against a platform ceiling at a fixed UTC instant.
 *
 * <p>Rules from the authorization contract: default deny; READ Allow implies META Allow; a META
 * Deny blocks READ as well; a READ Deny leaves META untouched; Deny beats Allow for the same
 * action; the result is intersected with the platform's own META/READ ceiling. Expired grants
 * contribute nothing, and a future grant only takes effect at its own start time.
 *
 * <p>{@code nextBoundaryAt} is the earliest future instant at which a relevant grant starts or
 * ends, so callers can invalidate a cached decision instead of relying on message delivery. Time
 * boundaries are compared in UTC with no local zone involved.
 */
public final class PermissionEvaluator {
    private PermissionEvaluator() {}

    public static Decision evaluate(
            List<PermissionGrant> grants, boolean platformMeta, boolean platformRead, Instant now) {
        Objects.requireNonNull(grants, "grants");
        Objects.requireNonNull(now, "now");

        Set<Long> allowIds = new TreeSet<>();
        Set<Long> denyIds = new TreeSet<>();
        boolean allowMeta = false;
        boolean allowRead = false;
        boolean denyMeta = false;
        boolean denyRead = false;
        Instant nextBoundary = null;

        for (PermissionGrant grant : grants) {
            Instant boundary = relevantBoundary(grant, now);
            if (boundary != null && (nextBoundary == null || boundary.isBefore(nextBoundary))) {
                nextBoundary = boundary;
            }
            if (!grant.covers(now)) {
                continue;
            }
            if (grant.effect() == PermissionGrant.Effect.DENY) {
                denyIds.add(grant.grantId());
                if (grant.action() == PermissionGrant.Action.READ) {
                    denyRead = true;
                } else {
                    denyMeta = true;
                }
            } else {
                allowIds.add(grant.grantId());
                if (grant.action() == PermissionGrant.Action.READ) {
                    allowRead = true;
                } else {
                    allowMeta = true;
                }
            }
        }

        // READ Allow implies META Allow; any META Deny blocks READ too.
        boolean meta = platformMeta && (allowMeta || allowRead) && !denyMeta;
        boolean read = platformRead && allowRead && !denyRead && !denyMeta;

        List<Long> allows = new ArrayList<>(allowIds);
        List<Long> denies = new ArrayList<>(denyIds);
        return new Decision(meta, read, allows, denies, Optional.ofNullable(nextBoundary));
    }

    private static Instant relevantBoundary(PermissionGrant grant, Instant now) {
        if (grant.validFrom().isAfter(now)) {
            return grant.validFrom();
        }
        if (grant.validTo() != null && grant.validTo().isAfter(now)) {
            return grant.validTo();
        }
        return null;
    }

    /** Effective decision plus provenance and the next time boundary, if any. */
    public record Decision(
            boolean meta,
            boolean read,
            List<Long> allowGrantIds,
            List<Long> denyGrantIds,
            Optional<Instant> nextBoundaryAt) {
        public Decision {
            allowGrantIds = List.copyOf(allowGrantIds);
            denyGrantIds = List.copyOf(denyGrantIds);
            Objects.requireNonNull(nextBoundaryAt, "nextBoundaryAt");
        }
    }
}
