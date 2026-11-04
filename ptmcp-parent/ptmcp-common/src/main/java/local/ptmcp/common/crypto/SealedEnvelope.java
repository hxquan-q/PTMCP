package local.ptmcp.common.crypto;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
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
 * another. {@code PTMC | 0x02 | ...} additionally binds an object context (a row identity, request
 * id or field name) so a blob sealed for one object cannot be presented as another object of the
 * same purpose. The older {@code iv | ciphertext || tag} layout, which is what every row written
 * before this format carries, is still accepted for reading so an upgrade does not orphan stored
 * data. Neither key material nor plaintext is logged.
 */
public final class SealedEnvelope {
    private static final int KEY_BYTES = 32;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final byte[] MAGIC = {0x50, 0x54, 0x4d, 0x43};
    private static final byte VERSION_PURPOSE = 0x01;
    private static final byte VERSION_CONTEXT = 0x02;
    private static final int HEADER_BYTES = MAGIC.length + 1;

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
        return seal(plaintext, purpose, null, cryptoFailure);
    }

    /**
     * Seals one plaintext under the write key with an object context bound into the authentication
     * tag. A non-null context produces format {@code 0x02}; callers that pass {@code null} keep
     * producing the older purpose-only format.
     */
    public byte[] seal(String plaintext, String purpose, String context, ErrorCode cryptoFailure) {
        if (writeKey == null) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        if (plaintext == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (context != null && context.isBlank()) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        byte version = context == null ? VERSION_PURPOSE : VERSION_CONTEXT;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, writeKey, new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(aad(purpose, context));
            byte[] body = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = new byte[HEADER_BYTES + iv.length + body.length];
            System.arraycopy(MAGIC, 0, sealed, 0, MAGIC.length);
            sealed[MAGIC.length] = version;
            System.arraycopy(iv, 0, sealed, HEADER_BYTES, iv.length);
            System.arraycopy(body, 0, sealed, HEADER_BYTES + iv.length, body.length);
            return sealed;
        } catch (GeneralSecurityException error) {
            throw new DomainException(cryptoFailure);
        }
    }

    /**
     * Opens a stored blob. The key is chosen by {@code storedKeyId}, so retired entries in the ring
     * still read the rows they sealed; a purpose that does not match the one used to seal fails the
     * authentication tag. A context-bound blob must be read with the same context; older
     * purpose-only and legacy blobs remain readable so an upgrade does not orphan stored data.
     */
    public String decrypt(
            byte[] sealed, String storedKeyId, String purpose, ErrorCode cryptoFailure) {
        return decrypt(sealed, storedKeyId, purpose, null, cryptoFailure);
    }

    /** Opens one stored blob with the object context bound at seal time. */
    public String decrypt(
            byte[] sealed,
            String storedKeyId,
            String purpose,
            String context,
            ErrorCode cryptoFailure) {
        if (sealed == null || sealed.length <= HEADER_BYTES + IV_BYTES) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        SecretKeySpec key = storedKeyId == null ? null : keys.get(storedKeyId);
        if (key == null) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        Format format = format(sealed);
        if (format == Format.UNKNOWN
                || (format == Format.CONTEXT && (context == null || context.isBlank()))) {
            // No versioned interpretation is available: an unknown version, or a context-bound
            // blob read without its context. The blob may still be a legacy row whose random IV
            // begins with the magic, so try the legacy layout before failing closed.
            format = Format.LEGACY;
        }
        try {
            return open(sealed, key, format, purpose, context);
        } catch (GeneralSecurityException versionedFailure) {
            if (format == Format.LEGACY) {
                throw new DomainException(cryptoFailure);
            }
            // A legacy blob's random IV can begin with the magic, making the versioned
            // interpretation fail the tag while the legacy one still opens it. Both attempts are
            // authenticated, so a purpose- or context-bound blob can never open as legacy.
            try {
                return open(sealed, key, Format.LEGACY, purpose, null);
            } catch (GeneralSecurityException legacyFailure) {
                throw new DomainException(cryptoFailure);
            }
        }
    }

    private String open(
            byte[] sealed, SecretKeySpec key, Format format, String purpose, String context)
            throws GeneralSecurityException {
        int ivOffset = format == Format.LEGACY ? 0 : HEADER_BYTES;
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                new GCMParameterSpec(TAG_BITS, sealed, ivOffset, IV_BYTES));
        if (format == Format.CONTEXT) {
            cipher.updateAAD(aad(purpose, context));
        } else if (format == Format.PURPOSE) {
            cipher.updateAAD(aad(purpose, null));
        }
        byte[] plain =
                cipher.doFinal(sealed, ivOffset + IV_BYTES, sealed.length - ivOffset - IV_BYTES);
        return new String(plain, StandardCharsets.UTF_8);
    }

    private static Format format(byte[] sealed) {
        if (sealed.length < MAGIC.length) {
            return Format.LEGACY;
        }
        for (int index = 0; index < MAGIC.length; index++) {
            if (sealed[index] != MAGIC[index]) {
                return Format.LEGACY;
            }
        }
        if (sealed.length < HEADER_BYTES) {
            return Format.UNKNOWN;
        }
        return switch (sealed[MAGIC.length]) {
            case VERSION_PURPOSE -> Format.PURPOSE;
            case VERSION_CONTEXT -> Format.CONTEXT;
            default -> Format.UNKNOWN;
        };
    }

    private enum Format {
        LEGACY,
        PURPOSE,
        CONTEXT,
        UNKNOWN
    }

    private static byte[] aad(String purpose, String context) {
        byte[] purposeBytes = purpose.getBytes(StandardCharsets.UTF_8);
        if (context == null) {
            return purposeBytes;
        }
        byte[] contextBytes = context.getBytes(StandardCharsets.UTF_8);
        byte[] aad = new byte[1 + purposeBytes.length + 1 + contextBytes.length];
        aad[0] = VERSION_CONTEXT;
        System.arraycopy(purposeBytes, 0, aad, 1, purposeBytes.length);
        aad[1 + purposeBytes.length] = 0;
        System.arraycopy(contextBytes, 0, aad, 2 + purposeBytes.length, contextBytes.length);
        return aad;
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
