package local.ptmcp.admin.datasource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import local.ptmcp.common.crypto.SealedEnvelope;
import local.ptmcp.common.crypto.SecretBindings;
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

/** Management secrets only open under the object identity that sealed them. */
class CredentialCipherTest {
    private static final String KEY = Base64.getEncoder().encodeToString(filled(32));

    @Test
    void roundTripsUnderTheSameObjectIdentity() {
        CredentialCipher cipher = new CredentialCipher(KEY);
        String binding = SecretBindings.datasource("orders");
        CredentialCipher.Sealed sealed = cipher.encrypt("s3cret", binding);

        assertEquals(0x02, sealed.ciphertext()[4]);
        assertEquals("s3cret", cipher.decrypt(sealed.ciphertext(), sealed.keyId(), binding));
    }

    @Test
    void ciphertextFromAnotherDatasourceFailsClosed() {
        CredentialCipher cipher = new CredentialCipher(KEY);
        CredentialCipher.Sealed sealed =
                cipher.encrypt("s3cret", SecretBindings.datasource("orders"));

        expect(
                () ->
                        cipher.decrypt(
                                sealed.ciphertext(),
                                sealed.keyId(),
                                SecretBindings.datasource("ledger")));
    }

    @Test
    void platformProofNeverOpensAsADatasourceCredential() {
        CredentialCipher cipher = new CredentialCipher(KEY);
        CredentialCipher.Sealed sealed =
                cipher.encrypt("s3cret", SecretBindings.platform("orders"));

        expect(
                () ->
                        cipher.decrypt(
                                sealed.ciphertext(),
                                sealed.keyId(),
                                SecretBindings.datasource("orders")));
    }

    @Test
    void purposeOnlyRowStaysReadableThroughTheObjectBoundCall() {
        SealedEnvelope writer = new SealedEnvelope(KEY, "ptmcp.admin.credential-key");
        byte[] sealed =
                writer.seal(
                        "s3cret",
                        SealedEnvelope.PURPOSE_MANAGEMENT_SECRET,
                        ErrorCode.INTERNAL_ERROR);
        assertEquals(0x01, sealed[4]);

        CredentialCipher cipher = new CredentialCipher(KEY);
        assertEquals(
                "s3cret",
                cipher.decrypt(sealed, writer.keyId(), SecretBindings.datasource("orders")));
    }

    @Test
    void legacyRowWithoutPrefixOrAadStaysReadable() throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
        gcm.init(
                Cipher.ENCRYPT_MODE,
                new SecretKeySpec(Base64.getDecoder().decode(KEY), "AES"),
                new GCMParameterSpec(128, iv));
        byte[] body = gcm.doFinal("s3cret".getBytes(StandardCharsets.UTF_8));
        byte[] sealed = new byte[iv.length + body.length];
        System.arraycopy(iv, 0, sealed, 0, iv.length);
        System.arraycopy(body, 0, sealed, iv.length, body.length);

        CredentialCipher cipher = new CredentialCipher(KEY);
        String keyId = cipher.encrypt("probe", SecretBindings.datasource("orders")).keyId();
        assertEquals("s3cret", cipher.decrypt(sealed, keyId, SecretBindings.datasource("orders")));
    }

    private static void expect(Runnable call) {
        DomainException failure = assertThrows(DomainException.class, call::run);
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, failure.errorCode());
    }

    private static byte[] filled(int length) {
        byte[] raw = new byte[length];
        Arrays.fill(raw, (byte) 9);
        return raw;
    }
}
