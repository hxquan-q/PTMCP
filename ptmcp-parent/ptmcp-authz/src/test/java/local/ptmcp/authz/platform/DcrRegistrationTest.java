package local.ptmcp.authz.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

/**
 * DCR: public-only, minimal scope, no self-claimed trust; redirect URIs are structurally strict.
 */
class DcrRegistrationTest {
    private final DcrRegistration registration = new DcrRegistration();

    @Test
    void minimalPublicRegistrationDefaultsToCatalogScopeAndNoneAuth() {
        DcrRegistration.RegisteredClient client =
                registration.register(
                        new DcrRegistration.DcrRequest(
                                List.of("https://app.example/callback"),
                                "none",
                                Set.of(),
                                null,
                                false));
        assertEquals("none", client.tokenEndpointAuthMethod());
        assertEquals(Set.of("mcp:catalog"), client.scopes());
        assertEquals(DcrRegistration.Tier.PUBLIC, client.tier());
        assertEquals(List.of("https://app.example/callback"), client.redirectUris());
    }

    @Test
    void scopeBeyondCatalogIsRejected() {
        DomainException error =
                assertThrows(
                        DomainException.class,
                        () ->
                                registration.register(
                                        new DcrRegistration.DcrRequest(
                                                List.of("https://app.example/callback"),
                                                "none",
                                                Set.of("mcp:catalog", "mcp:export"),
                                                null,
                                                false)));
        assertEquals(ErrorCode.UNSUPPORTED_FIELD, error.errorCode());
    }

    @Test
    void confidentialAuthMethodIsRejectedAsTier1Attempt() {
        for (String method :
                List.of(
                        "client_secret_basic",
                        "client_secret_post",
                        "private_key_jwt",
                        "tls_client_auth")) {
            DomainException error =
                    assertThrows(
                            DomainException.class,
                            () ->
                                    registration.register(
                                            new DcrRegistration.DcrRequest(
                                                    List.of("https://app.example/callback"),
                                                    method,
                                                    Set.of(),
                                                    null,
                                                    false)));
            assertEquals(ErrorCode.PLATFORM_PROOF_REQUIRED, error.errorCode(), method);
        }
    }

    @Test
    void selfClaimedPlatformIdOrTrustedBackendFlagIsRejected() {
        assertThrows(
                DomainException.class,
                () ->
                        registration.register(
                                new DcrRegistration.DcrRequest(
                                        List.of("https://app.example/callback"),
                                        "none",
                                        Set.of(),
                                        1L,
                                        false)));
        assertThrows(
                DomainException.class,
                () ->
                        registration.register(
                                new DcrRegistration.DcrRequest(
                                        List.of("https://app.example/callback"),
                                        "none",
                                        Set.of(),
                                        null,
                                        true)));
    }

    @Test
    void redirectUriWithFragmentUserinfoOrWildcardIsRejected() {
        for (String bad :
                List.of(
                        "https://app.example/callback#frag",
                        "https://user:pw@app.example/callback",
                        "https://app.example/*",
                        "https://app.example/callback*",
                        "https://*/callback",
                        "http://app.example/callback",
                        "ftp://app.example/callback",
                        "//app.example/callback",
                        "https://app.example/../other")) {
            DomainException error =
                    assertThrows(
                            DomainException.class,
                            () ->
                                    registration.register(
                                            new DcrRegistration.DcrRequest(
                                                    List.of(bad), "none", Set.of(), null, false)),
                            bad);
            assertEquals(ErrorCode.INVALID_ARGUMENT, error.errorCode(), bad);
        }
    }

    @Test
    void loopbackHttpRedirectsWithoutUserInfoAreAccepted() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () ->
                        registration.register(
                                new DcrRegistration.DcrRequest(
                                        List.of("http://127.0.0.1:8913/callback"),
                                        "none",
                                        Set.of(),
                                        null,
                                        false)));
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () ->
                        registration.register(
                                new DcrRegistration.DcrRequest(
                                        List.of("http://[::1]:8913/callback"),
                                        "none",
                                        Set.of(),
                                        null,
                                        false)));
    }

    @Test
    void redirectUriMustBePresentAndBounded() {
        assertThrows(
                DomainException.class,
                () ->
                        registration.register(
                                new DcrRegistration.DcrRequest(
                                        List.of(), "none", Set.of(), null, false)));
        List<String> tooMany = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tooMany.add("https://app" + i + ".example/callback");
        }
        assertThrows(
                DomainException.class,
                () ->
                        registration.register(
                                new DcrRegistration.DcrRequest(
                                        tooMany, "none", Set.of(), null, false)));
    }
}
