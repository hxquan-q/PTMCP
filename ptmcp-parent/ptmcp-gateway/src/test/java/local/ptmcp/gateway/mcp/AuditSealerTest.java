package local.ptmcp.gateway.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import local.ptmcp.common.error.DomainException;

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
        // 12-byte IV + at least the 16-byte tag + the plaintext length.
        assertTrue(sealed.length > 12 + 16);
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
        // The admin console reader (ptmcp-admin AuditCipher) uses the same derivation; verify the
        // wire format round-trips through an inline same-format decryptor.
        byte[] sealed = sealer.seal("SELECT id FROM orders WHERE id = 7");
        assertEquals("SELECT id FROM orders WHERE id = 7", decryptSameFormat(base64, sealed));
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
                () -> decryptSameFormat(Base64.getEncoder().encodeToString(otherRaw), sealed));
    }

    /** Same AES-GCM wire format and key-id derivation as the admin console's AuditCipher. */
    private static String decryptSameFormat(String base64Key, byte[] sealed) throws Exception {
        byte[] raw = Base64.getDecoder().decode(base64Key);
        javax.crypto.spec.SecretKeySpec key = new javax.crypto.spec.SecretKeySpec(raw, "AES");
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                javax.crypto.Cipher.DECRYPT_MODE,
                key,
                new javax.crypto.spec.GCMParameterSpec(128, sealed, 0, 12));
        return new String(
                cipher.doFinal(sealed, 12, sealed.length - 12),
                java.nio.charset.StandardCharsets.UTF_8);
    }
}
