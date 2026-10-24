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
        for (ResourceNode node : resourceTree) {
            PermissionEvaluator.Decision before =
                    PermissionEvaluator.evaluate(
                            GrantExpander.expand(grantsBefore, caller, resourceTree, node.id()),
                            platformMeta,
                            platformRead,
                            now);
            PermissionEvaluator.Decision after =
                    PermissionEvaluator.evaluate(
                            GrantExpander.expand(grantsAfter, caller, resourceTree, node.id()),
                            platformMeta,
                            platformRead,
                            now);
            if ((after.meta() && !before.meta()) || (after.read() && !before.read())) {
                throw new DomainException(ErrorCode.SELF_PRIVILEGE_ESCALATION);
            }
        }
    }
}
