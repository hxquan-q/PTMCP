package local.ptmcp.gateway.mcp;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM sealer for audit SQL plaintext on the gateway side. Wire format {@code iv ||
 * ciphertext || tag} with the same key-id derivation as the admin console's reader ({@code
 * local:<sha256-hex-16>}), so a deployment pairs one audit key across both sides. Without a
 * configured key the sealer stays disabled and terminals keep their ciphers null — the audit
 * console then simply has nothing to decrypt, which is the historical behavior.
 */
public final class AuditSealer {
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final String keyId;
    private final SecureRandom random = new SecureRandom();

    public AuditSealer(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            this.key = null;
            this.keyId = null;
            return;
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException("ptmcp.gateway.audit-key is not valid base64", error);
        }
        if (raw.length != KEY_BYTES) {
            throw new IllegalStateException("ptmcp.gateway.audit-key must decode to 32 bytes");
        }
        this.key = new SecretKeySpec(raw, "AES");
        this.keyId = "local:" + sha256Hex(raw).substring(0, 16);
    }

    public boolean enabled() {
        return key != null;
    }

    public String keyId() {
        return keyId;
    }

    /** Seals one plaintext; MAX_ENCRYPTED_RAW_BYTES is enforced by the terminal record. */
    public byte[] seal(String plaintext) {
        if (key == null) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] body = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = new byte[iv.length + body.length];
            System.arraycopy(iv, 0, sealed, 0, iv.length);
            System.arraycopy(body, 0, sealed, iv.length, body.length);
            return sealed;
        } catch (GeneralSecurityException error) {
            throw new DomainException(ErrorCode.AUDIT_UNAVAILABLE);
        }
    }

    private static String sha256Hex(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
