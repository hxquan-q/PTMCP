package local.ptmcp.admin.datasource;

import local.ptmcp.common.crypto.SealedEnvelope;
import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

/**
 * AES-GCM envelope for the management secrets written by the console: datasource credentials and
 * platform proof configuration. The deployment supplies one or more 256-bit keys as comma-separated
 * base64 through {@code ptmcp.admin.credential-key}: the first writes, the rest stay read-only so a
 * rotation keeps old rows decryptable. Format and key-id derivation live in {@link SealedEnvelope},
 * and the gateway side reads the same ring with its own copy of the key. Neither the key nor
 * plaintext is logged.
 */
public final class CredentialCipher {
    private final SealedEnvelope envelope;

    public CredentialCipher(String base64Keys) {
        this.envelope = new SealedEnvelope(base64Keys, "ptmcp.admin.credential-key");
    }

    public Sealed encrypt(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        return new Sealed(
                envelope.seal(
                        plaintext,
                        SealedEnvelope.PURPOSE_MANAGEMENT_SECRET,
                        ErrorCode.INTERNAL_ERROR),
                envelope.keyId());
    }

    public String decrypt(byte[] sealed, String storedKeyId) {
        return envelope.decrypt(
                sealed,
                storedKeyId,
                SealedEnvelope.PURPOSE_MANAGEMENT_SECRET,
                ErrorCode.DEPENDENCY_UNAVAILABLE);
    }

    public record Sealed(byte[] ciphertext, String keyId) {}
}
