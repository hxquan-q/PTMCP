package local.ptmcp.persistence.audit.masking;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

/**
 * Retention policy for raw/final SQL columns (audit.md §4): the ciphertext columns exist only when
 * the deployment explicitly enables them. {@code deploymentId} is the deployment/tenant identity
 * bound into every envelope AAD; it must be stable for the retention window and is never secret.
 */
public record SqlFieldCipherPolicy(boolean enabled, String deploymentId) {
    public SqlFieldCipherPolicy {
        if (enabled
                && (deploymentId == null
                        || deploymentId.isBlank()
                        || deploymentId.length() > 128)) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (!enabled && deploymentId != null && !deploymentId.isBlank()) {
            // Refuse loudly: a disabled policy with a deployment identity looks like a wiring
            // mistake, and silently dropping the identity would hide exactly that.
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (!enabled) {
            deploymentId = "";
        }
    }

    public static SqlFieldCipherPolicy disabled() {
        return new SqlFieldCipherPolicy(false, "");
    }
}
