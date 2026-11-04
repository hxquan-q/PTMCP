package local.ptmcp.admin.datasource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import local.ptmcp.common.crypto.SealedEnvelope;
import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** The admin reader must open every stored audit format through the same contexted call. */
class AuditCipherTest {
    private static final String REQUEST = "11111111-2222-3333-4444-555555555555";
    private static final String KEY = Base64.getEncoder().encodeToString(filled(32));

    @Test
    void opensContextBoundCipherAndRejectsAnotherField() {
        AuditCipher cipher = new AuditCipher(KEY);
        byte[] sealed = cipher.encryptForTesting("SELECT 1", REQUEST, "sqlRaw");
        assertEquals("SELECT 1", cipher.decrypt(sealed, cipher.keyIdOrNull(), REQUEST, "sqlRaw"));
        assertThrows(
                DomainException.class,
                () -> cipher.decrypt(sealed, cipher.keyIdOrNull(), REQUEST, "sqlFinal"));
    }

    @Test
    void opensPurposeOnlyRowThroughTheSameContextedCall() {
        SealedEnvelope writer = new SealedEnvelope(KEY, "ptmcp.admin.audit-key");
        byte[] sealed =
                writer.seal(
                        "SELECT 1", SealedEnvelope.PURPOSE_AUDIT_SQL, ErrorCode.AUDIT_UNAVAILABLE);
        assertEquals(0x01, sealed[4]);

        AuditCipher cipher = new AuditCipher(KEY);
        assertEquals("SELECT 1", cipher.decrypt(sealed, writer.keyId(), REQUEST, "sqlRaw"));
    }

    @Test
    void opensLegacyRowWithoutPrefixOrAadThroughTheSameContextedCall() throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
        gcm.init(
                Cipher.ENCRYPT_MODE,
                new SecretKeySpec(Base64.getDecoder().decode(KEY), "AES"),
                new GCMParameterSpec(128, iv));
        byte[] body = gcm.doFinal("SELECT 1".getBytes(StandardCharsets.UTF_8));
        byte[] sealed = new byte[iv.length + body.length];
        System.arraycopy(iv, 0, sealed, 0, iv.length);
        System.arraycopy(body, 0, sealed, iv.length, body.length);

        AuditCipher cipher = new AuditCipher(KEY);
        assertEquals("SELECT 1", cipher.decrypt(sealed, cipher.keyIdOrNull(), REQUEST, "sqlRaw"));
    }

    private static byte[] filled(int length) {
        byte[] raw = new byte[length];
        Arrays.fill(raw, (byte) 9);
        return raw;
    }
}
