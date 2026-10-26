package local.ptmcp.admin.datasource;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM reader for the audit SQL ciphers written by the execution plane. Same wire format as
 * {@link CredentialCipher} ({@code iv || ciphertext || tag}) and a matching key-id derivation, but
 * fed by its own deployment key ({@code ptmcp.admin.audit-key}): a compromised credential key must
 * not unlock audit plaintext. Without the key every decrypt fails closed.
 */
public final class AuditCipher {
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final String keyId;

    public AuditCipher(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            this.key = null;
            this.keyId = null;
            return;
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException("ptmcp.admin.audit-key is not valid base64", error);
        }
        if (raw.length != KEY_BYTES) {
            throw new IllegalStateException("ptmcp.admin.audit-key must decode to 32 bytes");
        }
        this.key = new SecretKeySpec(raw, "AES");
        this.keyId = "local:" + sha256Hex(raw).substring(0, 16);
    }

    /** The key id this deployment would have produced; compared against audit_log.key_id. */
    public String keyIdOrNull() {
        return keyId;
    }

    /** Decrypts a stored {@code iv || ciphertext || tag} blob; fails closed on any mismatch. */
    public String decrypt(byte[] sealed, String storedKeyId) {
        if (key == null || sealed == null || sealed.length <= IV_BYTES) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        if (keyId == null || !keyId.equals(storedKeyId == null ? null : storedKeyId.trim())) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(sealed, IV_BYTES, sealed.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException error) {
            throw new DomainException(ErrorCode.AUDIT_UNAVAILABLE);
        }
    }

    /** Encryption for test and tooling paths only; production writes come from the gateway. */
    public byte[] encryptForTesting(String plaintext) {
        if (key == null) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        byte[] iv = new byte[IV_BYTES];
        new java.security.SecureRandom().nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] body = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = new byte[iv.length + body.length];
            System.arraycopy(iv, 0, sealed, 0, iv.length);
            System.arraycopy(body, 0, sealed, iv.length, body.length);
            return sealed;
        } catch (GeneralSecurityException error) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR);
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
