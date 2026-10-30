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
        assertThrows(DomainException.class, () -> sealer.seal("SELECT 1"));
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
        byte[] sealed = sealer.seal("SELECT card_number FROM payment");
        // 5-byte version prefix + 12-byte IV + at least the 16-byte tag + the plaintext length.
        assertTrue(sealed.length > 5 + 12 + 16);
        assertTrue(sealer.keyId().startsWith("local:"));
        assertEquals(22, sealer.keyId().length());
        // Two seals of the same plaintext differ (random IV), like every AEAD usage.
        assertFalse(
                java.util.Arrays.equals(sealed, sealer.seal("SELECT card_number FROM payment")));
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
        byte[] sealed = sealer.seal("SELECT id FROM orders WHERE id = 7");
        assertEquals(
                "SELECT id FROM orders WHERE id = 7", decryptSameFormat(base64, sealed, expected));
    }

    @Test
    void wrongKeyFailsClosed() throws Exception {
        byte[] raw = new byte[32];
        new java.security.SecureRandom().nextBytes(raw);
        AuditSealer sealer = new AuditSealer(Base64.getEncoder().encodeToString(raw));
        byte[] sealed = sealer.seal("SELECT 1");
        byte[] otherRaw = new byte[32];
        new java.security.SecureRandom().nextBytes(otherRaw);
        assertThrows(
                Exception.class,
                () ->
                        decryptSameFormat(
                                Base64.getEncoder().encodeToString(otherRaw),
                                sealed,
                                sealer.keyId()));
    }

    /** A ciphertext re-sealed under a different purpose must not open. */
    @Test
    void otherPurposeCannotOpenAuditCipher() {
        String key = randomKey();
        AuditSealer sealer = new AuditSealer(key);
        byte[] sealed = sealer.seal("SELECT 1");
        SealedEnvelope management = new SealedEnvelope(key, "ptmcp.admin.credential-key");
        assertThrows(
                DomainException.class,
                () ->
                        management.decrypt(
                                sealed,
                                sealer.keyId(),
                                SealedEnvelope.PURPOSE_MANAGEMENT_SECRET,
                                ErrorCode.AUDIT_UNAVAILABLE));
    }

    /** Reads through the shared envelope, exactly as the admin console's AuditCipher does. */
    private static String decryptSameFormat(String base64Key, byte[] sealed, String keyId) {
        return new SealedEnvelope(base64Key, "test-key")
                .decrypt(
                        sealed,
                        keyId,
                        SealedEnvelope.PURPOSE_AUDIT_SQL,
                        ErrorCode.AUDIT_UNAVAILABLE);
    }
}
