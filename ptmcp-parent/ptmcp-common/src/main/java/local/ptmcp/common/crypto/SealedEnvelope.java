package local.ptmcp.common.crypto;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM envelope for every deployment secret the gateway seals: datasource credentials,
 * platform proof configuration and audit SQL ciphers. One implementation so a rotation, a format
 * fix or a review finding lands once instead of in four copies.
 *
 * <p>{@code commaSeparatedBase64Keys} is the value of a single configuration property: one or more
 * base64 256-bit keys. The first entry writes new ciphertext, the rest stay read-only, so a
 * rotation leaves old rows decryptable while a re-encryption pass drains them. The stored key id —
 * not the current configuration — selects which key reads a row. With no entry the envelope is
 * disabled and every call fails closed, which is the historical "key not configured" behavior.
 *
 * <p>New ciphertext is {@code PTMC | 0x01 | iv | ciphertext || tag}, where the purpose is bound as
 * GCM additional authenticated data so a blob sealed for one purpose cannot be presented as
 * another. The older {@code iv | ciphertext || tag} layout, which is what every row written before
 * this format carries, is still accepted for reading so an upgrade does not orphan stored data.
 * Neither key material nor plaintext is logged.
 */
public final class SealedEnvelope {
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final byte[] PREFIX = {0x50, 0x54, 0x4d, 0x43, 0x01};

    /** Bound as AAD for the keys that protect datasource credentials and platform proof config. */
    public static final String PURPOSE_MANAGEMENT_SECRET = "management-secret";

    /** Bound as AAD for audit SQL ciphers shared by the gateway writer and the audit reader. */
    public static final String PURPOSE_AUDIT_SQL = "audit-sql";

    private final Map<String, SecretKeySpec> keys = new HashMap<>();
    private final SecretKeySpec writeKey;
    private final String writeKeyId;
    private final SecureRandom random = new SecureRandom();

    /**
     * @param commaSeparatedBase64Keys raw configuration value; blank disables the envelope
     * @param property configuration key name, used only in the start-up failure message
     */
    public SealedEnvelope(String commaSeparatedBase64Keys, String property) {
        SecretKeySpec write = null;
        String writeId = null;
        String[] entries =
                commaSeparatedBase64Keys == null
                        ? new String[0]
                        : commaSeparatedBase64Keys.split(",");
        for (String entry : entries) {
            String value = entry.trim();
            if (value.isEmpty()) {
                continue;
            }
            byte[] raw;
            try {
                raw = Base64.getDecoder().decode(value);
            } catch (IllegalArgumentException error) {
                throw new IllegalStateException(property + " is not valid base64", error);
            }
            if (raw.length != KEY_BYTES) {
                throw new IllegalStateException(property + " must decode to 32 bytes");
            }
            String keyId = "local:" + sha256Hex(raw).substring(0, 16);
            keys.putIfAbsent(keyId, new SecretKeySpec(raw, "AES"));
            if (write == null) {
                write = keys.get(keyId);
                writeId = keyId;
            }
        }
        this.writeKey = write;
        this.writeKeyId = writeId;
    }

    /** True when a write key is configured; consumers keep their null ciphers without one. */
    public boolean enabled() {
        return writeKey != null;
    }

    /** Key id recorded alongside new ciphertext; null while disabled. */
    public String keyId() {
        return writeKeyId;
    }

    /**
     * Seals one plaintext under the write key.
     *
     * @param cryptoFailure code the caller uses for a cipher failure, so each consumer keeps its
     *     own error-code vocabulary
     */
    public byte[] seal(String plaintext, String purpose, ErrorCode cryptoFailure) {
        if (writeKey == null) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        if (plaintext == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, writeKey, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(purpose));
            byte[] body = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = new byte[PREFIX.length + iv.length + body.length];
            System.arraycopy(PREFIX, 0, sealed, 0, PREFIX.length);
            System.arraycopy(iv, 0, sealed, PREFIX.length, iv.length);
            System.arraycopy(body, 0, sealed, PREFIX.length + iv.length, body.length);
            return sealed;
        } catch (GeneralSecurityException error) {
            throw new DomainException(cryptoFailure);
        }
    }

    /**
     * Opens a stored blob. The key is chosen by {@code storedKeyId}, so retired entries in the ring
     * still read the rows they sealed; a purpose that does not match the one used to seal fails the
     * authentication tag.
     */
    public String decrypt(
            byte[] sealed, String storedKeyId, String purpose, ErrorCode cryptoFailure) {
        if (sealed == null || sealed.length <= PREFIX.length + IV_BYTES) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        SecretKeySpec key = storedKeyId == null ? null : keys.get(storedKeyId);
        if (key == null) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        boolean current = hasPrefix(sealed);
        int ivOffset = current ? PREFIX.length : 0;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    key,
                    new GCMParameterSpec(TAG_BITS, sealed, ivOffset, IV_BYTES));
            if (current) {
                cipher.updateAAD(aad(purpose));
            }
            byte[] plain =
                    cipher.doFinal(
                            sealed, ivOffset + IV_BYTES, sealed.length - ivOffset - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException error) {
            throw new DomainException(cryptoFailure);
        }
    }

    private static boolean hasPrefix(byte[] sealed) {
        if (sealed.length < PREFIX.length) {
            return false;
        }
        return Arrays.equals(PREFIX, Arrays.copyOfRange(sealed, 0, PREFIX.length));
    }

    // shortcut: purpose is a per-consumer constant, not row identity; upgrade once the owning row
    // id is threaded down to the cipher calls so same-type rows cannot swap ciphertexts.
    private static byte[] aad(String purpose) {
        return purpose.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256Hex(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** Stable identifier for a raw key, so every stored {@code key_id} survives a refactor. */
    public static String keyIdOf(byte[] rawKey) {
        if (rawKey == null || rawKey.length != KEY_BYTES) {
            throw new IllegalStateException("key must be 32 bytes");
        }
        return "local:" + sha256Hex(rawKey).substring(0, 16);
    }
}
