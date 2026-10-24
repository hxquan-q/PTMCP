package local.ptmcp.authz.oauth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/** Loads the single deployment signing key from PEM PKCS#8 and fails closed on any deviation. */
public final class RsaJwkSourceFactory {
    private static final String BEGIN = "-----BEGIN PRIVATE KEY-----";
    private static final String END = "-----END PRIVATE KEY-----";
    private static final int MIN_KEY_BITS = 2048;

    private RsaJwkSourceFactory() {}

    public static JWKSource<SecurityContext> fromPemFile(String file) {
        RSAKey key = readKey(file);
        JWKSet keys = new JWKSet(key);
        return (selector, context) -> selector.select(keys);
    }

    private static RSAKey readKey(String file) {
        if (file == null || file.isBlank()) {
            throw new IllegalStateException(
                    "ptmcp.authz.oauth.signing-key-file is required when OAuth is enabled");
        }
        String pem;
        try {
            pem = Files.readString(Path.of(file), StandardCharsets.US_ASCII);
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to read OAuth signing key", failure);
        }
        int begin = pem.indexOf(BEGIN);
        int end = pem.indexOf(END);
        if (begin < 0 || end < begin) {
            throw new IllegalStateException(
                    "OAuth signing key must be an unencrypted PEM PKCS#8 private key");
        }
        String body = pem.substring(begin + BEGIN.length(), end).replaceAll("\\s", "");
        byte[] der;
        try {
            der = Base64.getDecoder().decode(body);
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException("OAuth signing key is not valid base64", failure);
        }
        try {
            KeyFactory factory = KeyFactory.getInstance("RSA");
            RSAPrivateCrtKey privateKey =
                    (RSAPrivateCrtKey) factory.generatePrivate(new PKCS8EncodedKeySpec(der));
            RSAKey key =
                    new RSAKey.Builder(
                                    (java.security.interfaces.RSAPublicKey)
                                            factory.generatePublic(
                                                    new RSAPublicKeySpec(
                                                            privateKey.getModulus(),
                                                            privateKey.getPublicExponent())))
                            .privateKey(privateKey)
                            .keyUse(KeyUse.SIGNATURE)
                            .algorithm(JWSAlgorithm.RS256)
                            .keyIDFromThumbprint()
                            .build();
            if (key.size() < MIN_KEY_BITS) {
                throw new IllegalStateException(
                        "OAuth signing key must be at least " + MIN_KEY_BITS + " bits");
            }
            return key;
        } catch (ClassCastException failure) {
            throw new IllegalStateException(
                    "OAuth signing key must be an RSA private key", failure);
        } catch (GeneralSecurityException | JOSEException failure) {
            throw new IllegalStateException("OAuth signing key cannot be decoded", failure);
        }
    }
}
