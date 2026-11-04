package local.ptmcp.gateway.mcp;

import local.ptmcp.common.crypto.SealedEnvelope;
import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

/**
 * AES-256-GCM sealer for audit SQL plaintext on the gateway side. Same envelope and key-id
 * derivation as the admin console's reader, so a deployment pairs one audit key ring across both
 * sides. Without a configured key the sealer stays disabled and terminals keep their ciphers null —
 * the audit console then simply has nothing to decrypt, which is the historical behavior.
 */
public final class AuditSealer {
    private final SealedEnvelope envelope;

    public AuditSealer(String base64Keys) {
        this.envelope = new SealedEnvelope(base64Keys, "ptmcp.gateway.audit-key");
    }

    public boolean enabled() {
        return envelope.enabled();
    }

    public String keyId() {
        return envelope.keyId();
    }

    /**
     * Seals one audit SQL field, binding the owning request and the field name into the
     * authentication tag so a cipher cannot be replayed under another request or as the other SQL
     * field. MAX_ENCRYPTED_RAW_BYTES is enforced by the terminal record.
     */
    public byte[] seal(String plaintext, String requestId, String field) {
        if (requestId == null || field == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        return envelope.seal(
                plaintext,
                SealedEnvelope.PURPOSE_AUDIT_SQL,
                requestId + ":" + field,
                ErrorCode.AUDIT_UNAVAILABLE);
    }
}
