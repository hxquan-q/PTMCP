package local.ptmcp.gateway.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import local.ptmcp.common.crypto.SealedEnvelope;
import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import org.junit.jupiter.api.Test;

import java.util.Base64;

/** The sealer and the admin console reader must agree on format, key id and key pairing. */
class AuditSealerTest {
    private static final String REQUEST = "11111111-2222-3333-4444-555555555555";
    private static final String FIELD = "sqlRaw";

    private static String randomKey() {
        byte[] raw = new byte[32];
        new java.security.SecureRandom().nextBytes(raw);
        return Base64.getEncoder().encodeToString(raw);
    }

    @Test
    void disabledWithoutKeyAndFailsClosed() {
        AuditSealer sealer = new AuditSealer("");
        assertFalse(sealer.enabled());
        assertNull(sealer.keyId());
        assertThrows(DomainException.class, () -> sealer.seal("SELECT 1", REQUEST, FIELD));
    }

    @Test
    void rejectsWrongKeyLength() {
        assertThrows(
                IllegalStateException.class,
                () -> new AuditSealer(Base64.getEncoder().encodeToString(new byte[16])));
    }

    @Test
    void sealProducesIvPrefixedCipherWithStableKeyId() {
        AuditSealer sealer = new AuditSealer(randomKey());
        assertTrue(sealer.enabled());
        byte[] sealed = sealer.seal("SELECT card_number FROM payment", REQUEST, FIELD);
        // 5-byte version prefix + 12-byte IV + at least the 16-byte tag + the plaintext length.
        assertTrue(sealed.length > 5 + 12 + 16);
        assertEquals(0x02, sealed[4], "audit ciphers bind the owning request and field");
        assertTrue(sealer.keyId().startsWith("local:"));
        assertEquals(22, sealer.keyId().length());
        // Two seals of the same plaintext differ (random IV), like every AEAD usage.
        assertFalse(
                java.util.Arrays.equals(
                        sealed, sealer.seal("SELECT card_number FROM payment", REQUEST, FIELD)));
    }

    @Test
    void keyIdMatchesSha256DerivationForConsolePairing() throws Exception {
        byte[] raw = new byte[32];
        new java.security.SecureRandom().nextBytes(raw);
        String base64 = Base64.getEncoder().encodeToString(raw);
        AuditSealer sealer = new AuditSealer(base64);
        String expected =
                "local:"
                        + java.util.HexFormat.of()
                                .formatHex(
                                        java.security.MessageDigest.getInstance("SHA-256")
                                                .digest(raw))
                                .substring(0, 16);
        assertEquals(expected, sealer.keyId());
        // The admin console reader (ptmcp-admin AuditCipher) shares this envelope and purpose.
        byte[] sealed = sealer.seal("SELECT id FROM orders WHERE id = 7", REQUEST, FIELD);
        assertEquals(
                "SELECT id FROM orders WHERE id = 7",
                decryptSameFormat(base64, sealed, expected, REQUEST, FIELD));
    }

    /** An unknown stored key id cannot select a read key, so decryption fails before the tag. */
    @Test
    void unknownKeyIdFailsClosed() throws Exception {
        byte[] raw = new byte[32];
        new java.security.SecureRandom().nextBytes(raw);
        AuditSealer sealer = new AuditSealer(Base64.getEncoder().encodeToString(raw));
        byte[] sealed = sealer.seal("SELECT 1", REQUEST, FIELD);
        byte[] otherRaw = new byte[32];
        new java.security.SecureRandom().nextBytes(otherRaw);
        assertThrows(
                DomainException.class,
                () ->
                        decryptSameFormat(
                                Base64.getEncoder().encodeToString(otherRaw),
                                sealed,
                                sealer.keyId(),
                                REQUEST,
                                FIELD));
    }

    /** The correct key id with a tampered tag must fail GCM authentication. */
    @Test
    void tamperedCipherFailsAuthentication() {
        String key = randomKey();
        AuditSealer sealer = new AuditSealer(key);
        byte[] sealed = sealer.seal("SELECT 1", REQUEST, FIELD);
        sealed[sealed.length - 1] ^= 0x01;
        DomainException failure =
                assertThrows(
                        DomainException.class,
                        () -> decryptSameFormat(key, sealed, sealer.keyId(), REQUEST, FIELD));
        assertEquals(ErrorCode.AUDIT_UNAVAILABLE, failure.errorCode());
    }

    /** A ciphertext re-sealed under a different purpose must not open. */
    @Test
    void otherPurposeCannotOpenAuditCipher() {
        String key = randomKey();
        AuditSealer sealer = new AuditSealer(key);
        byte[] sealed = sealer.seal("SELECT 1", REQUEST, FIELD);
        SealedEnvelope management = new SealedEnvelope(key, "ptmcp.admin.credential-key");
        assertThrows(
                DomainException.class,
                () ->
                        management.decrypt(
                                sealed,
                                sealer.keyId(),
                                SealedEnvelope.PURPOSE_MANAGEMENT_SECRET,
                                REQUEST + ":" + FIELD,
                                ErrorCode.AUDIT_UNAVAILABLE));
    }

    /** The same request's other SQL field must not open this cipher. */
    @Test
    void anotherFieldCannotOpenTheSameCipher() {
        String key = randomKey();
        AuditSealer sealer = new AuditSealer(key);
        byte[] sealed = sealer.seal("SELECT 1", REQUEST, FIELD);
        assertThrows(
                DomainException.class,
                () -> decryptSameFormat(key, sealed, sealer.keyId(), REQUEST, "sqlFinal"));
    }

    /** Reads through the shared envelope, exactly as the admin console's AuditCipher does. */
    private static String decryptSameFormat(
            String base64Key, byte[] sealed, String keyId, String requestId, String field) {
        return new SealedEnvelope(base64Key, "test-key")
                .decrypt(
                        sealed,
                        keyId,
                        SealedEnvelope.PURPOSE_AUDIT_SQL,
                        requestId + ":" + field,
                        ErrorCode.AUDIT_UNAVAILABLE);
    }
}
