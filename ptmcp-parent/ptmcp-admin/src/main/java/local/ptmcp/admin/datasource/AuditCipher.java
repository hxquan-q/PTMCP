package local.ptmcp.admin.datasource;

import local.ptmcp.common.crypto.SealedEnvelope;
import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

/**
 * AES-256-GCM reader for the audit SQL ciphers written by the execution plane. Same envelope as
 * {@link CredentialCipher} and a matching key-id derivation, but fed by its own deployment key
 * ({@code ptmcp.admin.audit-key}: one or more comma-separated base64 keys, first writes, rest
 * read-only): a compromised credential key must not unlock audit plaintext. Without the key every
 * decrypt fails closed.
 */
public final class AuditCipher {
    private final SealedEnvelope envelope;

    public AuditCipher(String base64Keys) {
        this.envelope = new SealedEnvelope(base64Keys, "ptmcp.admin.audit-key");
    }

    /** The key id this deployment would have produced; compared against audit_log.key_id. */
    public String keyIdOrNull() {
        return envelope.keyId();
    }

    /** Opens a stored cipher; fails closed on any mismatch, including an unknown key id. */
    public String decrypt(byte[] sealed, String storedKeyId) {
        String keyId = storedKeyId == null ? null : storedKeyId.trim();
        if (!envelope.enabled()) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        return envelope.decrypt(
                sealed, keyId, SealedEnvelope.PURPOSE_AUDIT_SQL, ErrorCode.AUDIT_UNAVAILABLE);
    }

    /** Encryption for test and tooling paths only; production writes come from the gateway. */
    public byte[] encryptForTesting(String plaintext) {
        return envelope.seal(plaintext, SealedEnvelope.PURPOSE_AUDIT_SQL, ErrorCode.INTERNAL_ERROR);
    }
}
