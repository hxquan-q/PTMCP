package local.ptmcp.persistence.org;

import local.ptmcp.common.authz.GrantExpander;
import local.ptmcp.common.authz.GrantRow;
import local.ptmcp.common.authz.PermissionEvaluator;
import local.ptmcp.common.authz.ResourceNode;
import local.ptmcp.common.authz.SubjectScope;
import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Self-privilege-escalation guard for admin mutations. Before a change is admitted, the caller's
 * effective META/READ decision is evaluated for every node of the resource tree, before and after
 * the projected grant set. Any node that would newly gain META or READ for the caller is rejected
 * with {@code SELF_PRIVILEGE_ESCALATION}; losses and neutral changes pass. The caller is compared
 * as an ordinary subject — the platform ceiling supplied here is the caller's own platform view.
 */
public final class SelfEscalationGuard {
    private SelfEscalationGuard() {}

    public static void assertNoEscalation(
            SubjectScope caller,
            List<GrantRow> grantsBefore,
            List<GrantRow> grantsAfter,
            Collection<ResourceNode> resourceTree,
            boolean platformMeta,
            boolean platformRead,
            Instant now) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(grantsBefore, "grantsBefore");
        Objects.requireNonNull(grantsAfter, "grantsAfter");
        Objects.requireNonNull(resourceTree, "resourceTree");
        Objects.requireNonNull(now, "now");
        SortedSet<Instant> checkpoints = new TreeSet<>();
        checkpoints.add(now);
        for (List<GrantRow> grants : List.of(grantsBefore, grantsAfter)) {
            for (GrantRow grant : grants) {
                if (grant.validFrom().isAfter(now)) checkpoints.add(grant.validFrom());
                if (grant.validTo() != null && grant.validTo().isAfter(now)) {
                    checkpoints.add(grant.validTo());
                }
            }
        }
        for (ResourceNode node : resourceTree) {
            var beforeGrants = GrantExpander.expand(grantsBefore, caller, resourceTree, node.id());
            var afterGrants = GrantExpander.expand(grantsAfter, caller, resourceTree, node.id());
            // Decisions are constant between half-open grant boundaries, including Deny expiry.
            for (Instant checkpoint : checkpoints) {
                PermissionEvaluator.Decision before =
                        PermissionEvaluator.evaluate(
                                beforeGrants, platformMeta, platformRead, checkpoint);
                PermissionEvaluator.Decision after =
                        PermissionEvaluator.evaluate(
                                afterGrants, platformMeta, platformRead, checkpoint);
                if ((after.meta() && !before.meta()) || (after.read() && !before.read())) {
                    throw new DomainException(ErrorCode.SELF_PRIVILEGE_ESCALATION);
                }
            }
        }
    }
}
