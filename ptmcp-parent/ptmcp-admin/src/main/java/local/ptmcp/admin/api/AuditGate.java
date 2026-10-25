package local.ptmcp.admin.api;

import jakarta.servlet.http.HttpServletRequest;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.org.DelegationStore;

import java.time.Instant;

/**
 * Permission gates for the audit admin surface. The three operations are separate permissions:
 * audit:list, audit:read and audit:decrypt. Platform admins hold all three; delegated admins need
 * an explicit delegation whose allowedActions covers the action AND whose resource scope (the
 * datasource) covers the audit record. Decrypt is never granted by the platform role alone —
 * audit.md §5: 所有角色默认无解密权，须明确另授.
 */
public final class AuditGate {
    public static final String ACTION_LIST = "audit:list";
    public static final String ACTION_READ = "audit:read";
    public static final String ACTION_DECRYPT = "audit:decrypt";

    private AuditGate() {}

    /** Platform admins pass unconditionally for list/read; decrypt always needs delegation. */
    public static boolean isAllowed(
            AdminPrincipal principal,
            DelegationStore delegations,
            String action,
            Long datasourceId) {
        if (principal.platformAdmin() && !ACTION_DECRYPT.equals(action)) {
            return true;
        }
        if (datasourceId == null) {
            return false;
        }
        DelegationStore.DelegationRecord delegation =
                delegations.findValid(
                        principal.userId(), "DATASOURCE", datasourceId, Instant.now());
        return delegation != null && delegation.coversAction(action);
    }

    public static void requireAllowed(
            HttpServletRequest request,
            DelegationStore delegations,
            String action,
            Long datasourceId) {
        AdminPrincipal principal = AdminPrincipal.require(request);
        if (!isAllowed(principal, delegations, action, datasourceId)) {
            throw new DomainException(ErrorCode.MANAGEMENT_SCOPE_DENIED);
        }
    }
}
