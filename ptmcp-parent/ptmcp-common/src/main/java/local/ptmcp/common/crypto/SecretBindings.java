package local.ptmcp.common.crypto;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

/**
 * Object identities bound into management-secret ciphertexts. The code is the immutable, unique
 * identity of a datasource or platform, so a writer and every reader derive the same context from
 * the row itself instead of trusting a separately stored value. Copying a ciphertext to another
 * object therefore fails the authentication tag.
 */
public final class SecretBindings {
    private SecretBindings() {}

    public static String datasource(String code) {
        return binding("datasource", code);
    }

    public static String platform(String code) {
        return binding("platform", code);
    }

    private static String binding(String kind, String code) {
        if (code == null || code.isBlank()) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR);
        }
        return kind + ":" + code;
    }
}
