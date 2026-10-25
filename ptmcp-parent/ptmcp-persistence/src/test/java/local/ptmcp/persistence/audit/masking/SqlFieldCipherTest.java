package local.ptmcp.persistence.audit.masking;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.audit.wal.WalKey;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Envelope encryption for sql_raw/sql_final (audit.md §4): independent nonces, AAD bound to request
 * id + field + schema version + deployment identity, safe failure posture and a disabled policy
 * that behaves as "nothing stored".
 */
class SqlFieldCipherTest {
    private static final UUID REQUEST = UUID.fromString("7b6c4f11-0000-4000-8000-000000000001");
    private static final WalKey KEY = new WalKey("k-2024-11", new byte[32]);
    private static final SqlFieldCipherPolicy POLICY = new SqlFieldCipherPolicy(true, "deploy-7");

    @Test
    void roundtripsAndBindsEveryAadDimension() {
        byte[] plain = "SELECT * FROM t WHERE a = 'v'".getBytes(StandardCharsets.UTF_8);
        byte[] envelope =
                SqlFieldCipher.encrypt(POLICY, KEY, REQUEST, SqlFieldCipher.FIELD_SQL_RAW, plain);

        assertNotNull(envelope);
        assertEquals(1, envelope[0]);
        assertArrayEquals(
                plain,
                SqlFieldCipher.decrypt(
                        POLICY, KEY, REQUEST, SqlFieldCipher.FIELD_SQL_RAW, envelope));

        // Every AAD dimension is load-bearing: a mismatch on any one of them must refuse.
        UUID other = UUID.fromString("7b6c4f11-0000-4000-8000-000000000002");
        assertRefuses(
                () ->
                        SqlFieldCipher.decrypt(
                                POLICY, KEY, other, SqlFieldCipher.FIELD_SQL_RAW, envelope));
        assertRefuses(
                () ->
                        SqlFieldCipher.decrypt(
                                POLICY, KEY, REQUEST, SqlFieldCipher.FIELD_SQL_FINAL, envelope));
        assertRefuses(
                () ->
                        SqlFieldCipher.decrypt(
                                new SqlFieldCipherPolicy(true, "deploy-8"),
                                KEY,
                                REQUEST,
                                SqlFieldCipher.FIELD_SQL_RAW,
                                envelope));
        assertRefuses(
                () ->
                        SqlFieldCipher.decrypt(
                                POLICY,
                                new WalKey("k-other", zeroButDifferentKeyBytes()),
                                REQUEST,
                                SqlFieldCipher.FIELD_SQL_RAW,
                                envelope));

        // Byte-level tampering fails the tag check; truncated envelopes fail closed.
        byte[] tampered = envelope.clone();
        tampered[tampered.length - 1] ^= 0x40;
        assertRefuses(
                () ->
                        SqlFieldCipher.decrypt(
                                POLICY, KEY, REQUEST, SqlFieldCipher.FIELD_SQL_RAW, tampered));
        assertRefuses(
                () ->
                        SqlFieldCipher.decrypt(
                                POLICY,
                                KEY,
                                REQUEST,
                                SqlFieldCipher.FIELD_SQL_RAW,
                                Arrays.copyOf(envelope, 10)));
        assertRefuses(
                () ->
                        SqlFieldCipher.decrypt(
                                POLICY, KEY, REQUEST, SqlFieldCipher.FIELD_SQL_RAW, new byte[0]));
    }

    @Test
    void noncesNeverRepeatAndEnvelopesDiffer() {
        byte[] plain = "SELECT 1".getBytes(StandardCharsets.UTF_8);
        Set<String> nonces = new HashSet<>();
        Set<String> envelopes = new HashSet<>();
        for (int index = 0; index < 100; index++) {
            byte[] envelope =
                    SqlFieldCipher.encrypt(
                            POLICY, KEY, REQUEST, SqlFieldCipher.FIELD_SQL_RAW, plain);
            String hex = java.util.HexFormat.of().formatHex(envelope, 1, 13);
            assertTrue(nonces.add(hex), "nonce reuse at iteration " + index);
            envelopes.add(java.util.HexFormat.of().formatHex(envelope));
        }
        assertEquals(100, envelopes.size());
    }

    @Test
    void ciphertextNeverContainsPlaintext() {
        byte[] plain = "SELECT 'TopSecretValue-9f3a' FROM t".getBytes(StandardCharsets.UTF_8);
        byte[] envelope =
                SqlFieldCipher.encrypt(POLICY, KEY, REQUEST, SqlFieldCipher.FIELD_SQL_RAW, plain);
        String hexEnvelope = java.util.HexFormat.of().formatHex(envelope);
        for (byte value : "TopSecretValue-9f3a".getBytes(StandardCharsets.UTF_8)) {
            String hexByte = String.format("%02x", value);
            assertFalse(
                    hexEnvelope.contains(hexByte + hexByte + hexByte + hexByte),
                    "plaintext pattern leaked");
        }
        String asciiEnvelope = new String(envelope, StandardCharsets.ISO_8859_1);
        assertFalse(asciiEnvelope.contains("TopSecretValue"));
    }

    @Test
    void disabledPolicyBehavesAsNothingStored() {
        SqlFieldCipherPolicy disabled = SqlFieldCipherPolicy.disabled();
        byte[] plain = "SELECT 1".getBytes(StandardCharsets.UTF_8);
        assertNull(
                SqlFieldCipher.encrypt(
                        disabled, KEY, REQUEST, SqlFieldCipher.FIELD_SQL_RAW, plain));
        assertNull(
                SqlFieldCipher.decrypt(
                        disabled, KEY, REQUEST, SqlFieldCipher.FIELD_SQL_RAW, new byte[24]));
        // A disabled policy never accepts a foreign deployment identity to bind later.
        assertThrows(Exception.class, () -> new SqlFieldCipherPolicy(false, "deploy-x"));
    }

    @Test
    void inputBudgetIsEnforcedBeforeEncryption() {
        byte[] ok = new byte[SqlFieldCipher.MAX_PLAINTEXT_BYTES];
        Arrays.fill(ok, (byte) 'a');
        byte[] envelope =
                SqlFieldCipher.encrypt(POLICY, KEY, REQUEST, SqlFieldCipher.FIELD_SQL_FINAL, ok);
        assertNotNull(envelope);

        byte[] tooBig = new byte[SqlFieldCipher.MAX_PLAINTEXT_BYTES + 1];
        Arrays.fill(tooBig, (byte) 'a');
        DomainException oversized =
                assertThrows(
                        DomainException.class,
                        () ->
                                SqlFieldCipher.encrypt(
                                        POLICY,
                                        KEY,
                                        REQUEST,
                                        SqlFieldCipher.FIELD_SQL_FINAL,
                                        tooBig));
        assertEquals(ErrorCode.INVALID_ARGUMENT, oversized.errorCode());

        byte[] withNul = "a\0b".getBytes(StandardCharsets.UTF_8);
        assertThrows(
                DomainException.class,
                () ->
                        SqlFieldCipher.encrypt(
                                POLICY, KEY, REQUEST, SqlFieldCipher.FIELD_SQL_FINAL, withNul));

        assertThrows(
                DomainException.class,
                () ->
                        SqlFieldCipher.encrypt(
                                POLICY,
                                KEY,
                                REQUEST,
                                "sql_result_rows",
                                "rows".getBytes(StandardCharsets.UTF_8)));
        assertNull(
                SqlFieldCipher.encrypt(POLICY, KEY, REQUEST, SqlFieldCipher.FIELD_SQL_FINAL, null));
    }

    /** A different 256-bit key, not merely a different key id over the same bytes. */
    private static byte[] zeroButDifferentKeyBytes() {
        byte[] key = new byte[32];
        key[0] = 0x5A;
        key[31] = 0x7F;
        return key;
    }

    private static void assertRefuses(Runnable attempt) {
        DomainException failure = assertThrows(DomainException.class, attempt::run);
        assertEquals(ErrorCode.AUDIT_UNAVAILABLE, failure.errorCode());
    }
}
