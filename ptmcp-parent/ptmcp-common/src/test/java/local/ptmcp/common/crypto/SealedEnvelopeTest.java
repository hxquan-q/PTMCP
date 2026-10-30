package local.ptmcp.common.crypto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

class SealedEnvelopeTest {
    private static final String PURPOSE = "datasource-credential";
    private static final ErrorCode FAIL = ErrorCode.DEPENDENCY_UNAVAILABLE;
    private static final String KEY_A = base64(7);
    private static final String KEY_B = base64(8);

    @Test
    void roundTripsCurrentFormat() {
        SealedEnvelope envelope = new SealedEnvelope(KEY_A, "test-key");
        byte[] sealed = envelope.seal("s3cret", PURPOSE, FAIL);
        assertTrue(envelope.enabled());
        assertEquals(envelope.keyId(), keyId(KEY_A));
        assertEquals("s3cret", envelope.decrypt(sealed, envelope.keyId(), PURPOSE, FAIL));
    }

    @Test
    void readsLegacyBlobWithoutPrefixOrAad() throws Exception {
        byte[] raw = Base64.getDecoder().decode(KEY_A);
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(
                Cipher.ENCRYPT_MODE, new SecretKeySpec(raw, "AES"), new GCMParameterSpec(128, iv));
        byte[] body = cipher.doFinal("old-row".getBytes(StandardCharsets.UTF_8));
        byte[] legacy = new byte[iv.length + body.length];
        System.arraycopy(iv, 0, legacy, 0, iv.length);
        System.arraycopy(body, 0, legacy, iv.length, body.length);

        SealedEnvelope envelope = new SealedEnvelope(KEY_A, "test-key");
        assertEquals("old-row", envelope.decrypt(legacy, keyId(KEY_A), PURPOSE, FAIL));
    }

    @Test
    void retiredKeyStillReadsItsRowsAndNewWritesUseFirstEntry() {
        byte[] before = new SealedEnvelope(KEY_B, "test-key").seal("before", PURPOSE, FAIL);

        SealedEnvelope envelope = new SealedEnvelope(KEY_A + "," + KEY_B, "test-key");
        assertEquals(keyId(KEY_A), envelope.keyId(), "first entry writes");
        assertEquals("before", envelope.decrypt(before, keyId(KEY_B), PURPOSE, FAIL));
        assertEquals(
                "after",
                envelope.decrypt(
                        envelope.seal("after", PURPOSE, FAIL), keyId(KEY_A), PURPOSE, FAIL));
    }

    @Test
    void purposeMismatchFailsAuthentication() {
        SealedEnvelope envelope = new SealedEnvelope(KEY_A, "test-key");
        byte[] sealed = envelope.seal("payload", PURPOSE, FAIL);
        assertThrows(
                DomainException.class,
                () -> envelope.decrypt(sealed, envelope.keyId(), "audit-sql", FAIL));
    }

    @Test
    void unknownOrMissingKeyIdFailsClosed() {
        SealedEnvelope envelope = new SealedEnvelope(KEY_A, "test-key");
        byte[] sealed = envelope.seal("payload", PURPOSE, FAIL);
        DomainException unknown =
                assertThrows(
                        DomainException.class,
                        () -> envelope.decrypt(sealed, "local:0000000000000000", PURPOSE, FAIL));
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, unknown.errorCode());
        assertThrows(DomainException.class, () -> envelope.decrypt(sealed, null, PURPOSE, FAIL));
        assertThrows(
                DomainException.class,
                () -> envelope.decrypt(new byte[4], envelope.keyId(), PURPOSE, FAIL));
    }

    @Test
    void tamperedCiphertextFailsWithCallersCode() {
        SealedEnvelope envelope = new SealedEnvelope(KEY_A, "test-key");
        byte[] sealed = envelope.seal("payload", PURPOSE, ErrorCode.AUDIT_UNAVAILABLE);
        sealed[sealed.length - 1] ^= 0x01;
        DomainException failure =
                assertThrows(
                        DomainException.class,
                        () ->
                                envelope.decrypt(
                                        sealed,
                                        envelope.keyId(),
                                        PURPOSE,
                                        ErrorCode.AUDIT_UNAVAILABLE));
        assertEquals(ErrorCode.AUDIT_UNAVAILABLE, failure.errorCode());
    }

    @Test
    void blankRingIsDisabled() {
        SealedEnvelope envelope = new SealedEnvelope(" , ", "test-key");
        assertFalse(envelope.enabled());
        assertNull(envelope.keyId());
        assertThrows(DomainException.class, () -> envelope.seal("x", PURPOSE, FAIL));
    }

    @Test
    void nullPlaintextIsInvalidArgument() {
        SealedEnvelope envelope = new SealedEnvelope(KEY_A, "test-key");
        DomainException failure =
                assertThrows(DomainException.class, () -> envelope.seal(null, PURPOSE, FAIL));
        assertEquals(ErrorCode.INVALID_ARGUMENT, failure.errorCode());
    }

    @Test
    void rejectsBadKeyConfiguration() {
        assertThrows(
                IllegalStateException.class,
                () -> new SealedEnvelope("not-base64!!", "ptmcp.x.key"));
        IllegalStateException wrongLength =
                assertThrows(
                        IllegalStateException.class,
                        () -> new SealedEnvelope("AAAA", "ptmcp.x.key"));
        assertTrue(wrongLength.getMessage().contains("ptmcp.x.key"), wrongLength.getMessage());
    }

    private static String base64(int fill) {
        byte[] raw = new byte[32];
        Arrays.fill(raw, (byte) fill);
        return Base64.getEncoder().encodeToString(raw);
    }

    private static String keyId(String base64Key) {
        try {
            byte[] digest =
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(Base64.getDecoder().decode(base64Key));
            return "local:" + HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
