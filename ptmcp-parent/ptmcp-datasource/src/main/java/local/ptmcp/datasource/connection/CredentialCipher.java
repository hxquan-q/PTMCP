package local.ptmcp.datasource.connection;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-GCM envelope for datasource credentials. The deployment supplies one 256-bit key as base64;
 * ciphertext is {@code iv || ciphertext || tag}. Neither the key nor plaintext is logged.
 */
public final class CredentialCipher {
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final String keyId;
    private final SecureRandom random = new SecureRandom();

    public CredentialCipher(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            this.key = null;
            this.keyId = null;
            return;
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException(
                    "ptmcp.admin.credential-key is not valid base64", error);
        }
        if (raw.length != KEY_BYTES) {
            throw new IllegalStateException("ptmcp.admin.credential-key must decode to 32 bytes");
        }
        this.key = new SecretKeySpec(raw, "AES");
        this.keyId = "local:" + HexFormat.of().formatHex(digest(raw)).substring(0, 16);
    }

    public Sealed encrypt(String plaintext) {
        if (key == null) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        if (plaintext == null || plaintext.isBlank()) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
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
            return new Sealed(sealed, keyId);
        } catch (GeneralSecurityException error) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR);
        }
    }

    public String decrypt(byte[] sealed, String storedKeyId) {
        if (key == null || sealed == null || sealed.length <= IV_BYTES) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        if (!keyId.equals(storedKeyId)) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(sealed, IV_BYTES, sealed.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    public record Sealed(byte[] ciphertext, String keyId) {}

    private static byte[] digest(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException(error);
        }
    }
}
