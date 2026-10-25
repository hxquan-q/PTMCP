package local.ptmcp.persistence.audit.masking;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.audit.wal.WalKey;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.UUID;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM envelope encryption for the {@code sql_raw}/{@code sql_final} audit columns (audit.md
 * §4). One random 96-bit nonce per envelope; the GCM AAD binds the deployment/tenant identity, the
 * column field name, the request id and the envelope schema version, so a ciphertext copied to
 * another request, column, schema version or deployment fails the tag check instead of decrypting.
 * The key id is returned next to the envelope and stored in {@code audit_log.key_id}; data keys
 * come from {@link WalKey} (KMS-wrapped provider), never from this class.
 *
 * <p>Failure posture: any decryption problem (tamper, wrong binding, truncated envelope, unknown
 * schema) throws {@code AUDIT_UNAVAILABLE} — the caller keeps its safe terminal state and never
 * sees plaintext. Encryption is refused for null/empty plaintext, plaintext above the 64 KiB
 * masking budget, or plaintext containing NUL bytes; a caller that cannot prove the budget has no
 * business storing raw SQL. When the policy is disabled both directions return null: nothing is
 * retained and nothing is decrypted.
 */
public final class SqlFieldCipher {
    public static final byte SCHEMA_VERSION = 1;
    public static final String FIELD_SQL_RAW = "sql_raw";
    public static final String FIELD_SQL_FINAL = "sql_final";
    public static final int MAX_PLAINTEXT_BYTES = 64 * 1024;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int VERSION_BYTES = 1;
    private static final String AAD_PREFIX = "ptmcp-audit-sql/v1";
    private static final String[] FIELDS = {FIELD_SQL_RAW, FIELD_SQL_FINAL};

    private static final SecureRandom RANDOM = new SecureRandom();

    private SqlFieldCipher() {}

    /**
     * Encrypts one field value. {@code null} plaintext is the "not retained" case and returns null.
     * The result layout is {@code [u8 schemaVersion][12 nonce][cipher]}.
     */
    public static byte[] encrypt(
            SqlFieldCipherPolicy policy,
            WalKey key,
            UUID requestId,
            String field,
            byte[] plaintext) {
        Objects.requireNonNull(policy, "policy");
        if (plaintext == null || !policy.enabled()) {
            // Disabled policy means nothing is retained; callers see the same null they passed.
            return null;
        }
        validateField(field);
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(key, "key");
        if (plaintext.length == 0) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (plaintext.length > MAX_PLAINTEXT_BYTES) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        for (byte value : plaintext) {
            if (value == 0) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
        }
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        byte[] aad = aad(policy, requestId, field);
        byte[] cipher;
        try {
            Cipher instance = Cipher.getInstance("AES/GCM/NoPadding");
            instance.init(
                    Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key.aesKey(), "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            instance.updateAAD(aad);
            cipher = instance.doFinal(plaintext);
        } catch (AEADBadTagException failure) {
            throw new DomainException(ErrorCode.AUDIT_UNAVAILABLE);
        } catch (Exception failure) {
            throw new DomainException(ErrorCode.AUDIT_UNAVAILABLE);
        }
        ByteBuffer envelope = ByteBuffer.allocate(VERSION_BYTES + NONCE_BYTES + cipher.length);
        envelope.put(SCHEMA_VERSION);
        envelope.put(nonce);
        envelope.put(cipher);
        return envelope.array();
    }

    /**
     * Decrypts one envelope produced by {@link #encrypt}. Every verification failure is {@code
     * AUDIT_UNAVAILABLE}; null in, null out.
     */
    public static byte[] decrypt(
            SqlFieldCipherPolicy policy,
            WalKey key,
            UUID requestId,
            String field,
            byte[] envelope) {
        Objects.requireNonNull(policy, "policy");
        if (envelope == null || !policy.enabled()) {
            return null;
        }
        validateField(field);
        Objects.requireNonNull(key, "key");
        if (envelope.length < VERSION_BYTES + NONCE_BYTES + 16) {
            throw new DomainException(ErrorCode.AUDIT_UNAVAILABLE);
        }
        ByteBuffer buffer = ByteBuffer.wrap(envelope);
        if (buffer.get() != SCHEMA_VERSION) {
            throw new DomainException(ErrorCode.AUDIT_UNAVAILABLE);
        }
        byte[] nonce = new byte[NONCE_BYTES];
        buffer.get(nonce);
        byte[] cipher = new byte[buffer.remaining()];
        buffer.get(cipher);
        byte[] aad = aad(policy, requestId, field);
        try {
            Cipher instance = Cipher.getInstance("AES/GCM/NoPadding");
            instance.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key.aesKey(), "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            instance.updateAAD(aad);
            return instance.doFinal(cipher);
        } catch (AEADBadTagException failure) {
            throw new DomainException(ErrorCode.AUDIT_UNAVAILABLE);
        } catch (Exception failure) {
            throw new DomainException(ErrorCode.AUDIT_UNAVAILABLE);
        }
    }

    private static byte[] aad(SqlFieldCipherPolicy policy, UUID requestId, String field) {
        byte[] identity = policy.deploymentId().getBytes(StandardCharsets.UTF_8);
        byte[] fieldBytes = field.getBytes(StandardCharsets.UTF_8);
        ByteBuffer aad =
                ByteBuffer.allocate(
                        AAD_PREFIX.length()
                                + 1
                                + identity.length
                                + 1
                                + fieldBytes.length
                                + 1
                                + 16
                                + 1
                                + 1);
        aad.put(AAD_PREFIX.getBytes(StandardCharsets.UTF_8));
        aad.put((byte) '\n');
        aad.put(identity);
        aad.put((byte) '\n');
        aad.put(fieldBytes);
        aad.put((byte) '\n');
        aad.putLong(requestId.getMostSignificantBits());
        aad.putLong(requestId.getLeastSignificantBits());
        aad.put((byte) '\n');
        aad.put(SCHEMA_VERSION);
        return aad.array();
    }

    /**
     * Decrypts one audit field for the admin API decrypt path using API field names
     * ("sqlRaw"/"sqlFinal") instead of the internal column names.
     */
    public static byte[] decryptForAudit(
            SqlFieldCipherPolicy policy,
            WalKey key,
            UUID requestId,
            String apiFieldName,
            byte[] envelope) {
        String field =
                "sqlRaw".equals(apiFieldName)
                        ? FIELD_SQL_RAW
                        : "sqlFinal".equals(apiFieldName) ? FIELD_SQL_FINAL : null;
        if (field == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        return decrypt(policy, key, requestId, field, envelope);
    }

    private static void validateField(String field) {
        if (field == null || !(FIELD_SQL_RAW.equals(field) || FIELD_SQL_FINAL.equals(field))) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
    }
}
