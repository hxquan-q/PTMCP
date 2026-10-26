package local.ptmcp.authz.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import local.ptmcp.authz.AuthzApplication;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigInteger;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Real MySQL and real HTTP coverage for the code-only authorization server: AS login, consent, code
 * exchange, PKCE, JWKS and the OAuth error surface. Every value is synthetic.
 */
@SpringBootTest(
        classes = AuthzApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class OAuthAuthorizationCodeFlowTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String MYSQL_IMAGE =
            "mysql@sha256:76f0358884cb92d9a086d24e5671c38048d3fef40f2588703d73eb55392d6b69";
    private static final String AUDIENCE = "ptmcp-mcp";
    private static final String CONFIDENTIAL_CLIENT_ID = "confidential-client";
    private static final String PUBLIC_CLIENT_ID = "public-client";
    private static final String ESCAPE_CLIENT_ID = "escape-client";
    private static final String REFRESH_CLIENT_ID = "refresh-client";
    private static final String CONFIDENTIAL_CLIENT_ROW = "rc-confidential";
    private static final String PUBLIC_CLIENT_ROW = "rc-public";
    private static final String ESCAPE_CLIENT_ROW = "rc-escape";
    private static final String REFRESH_CLIENT_ROW = "rc-refresh";
    private static final String CONFIDENTIAL_REDIRECT = "https://app.example/callback";
    private static final String PUBLIC_REDIRECT = "http://127.0.0.1:7777/callback";
    private static final String ESCAPE_REDIRECT = "http://127.0.0.1:7778/callback";
    private static final String REFRESH_REDIRECT = "http://127.0.0.1:7779/callback";
    private static final String SCOPES = "mcp:query mcp:catalog";
    private static final String CLIENT_SCOPES = "mcp:query,mcp:catalog";
    private static final String PUBLIC_SCOPES = "mcp:query";
    private static final String PUBLIC_CLIENT_SCOPES = "mcp:query";
    private static final String USERNAME = "alice";
    private static final long USER_ID = 42L;
    private static final String PASSWORD = randomToken(32);
    private static final String CLIENT_SECRET = randomToken(32);
    private static final int PORT = freePort();
    private static final String ISSUER = "http://127.0.0.1:" + PORT;
    private static final Path SIGNING_KEY = generateSigningKey();
    private static final MySQLContainer MYSQL = startMysql();
    private static final HttpClient HTTP =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
    private static final Pattern CSRF_VALUE = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

    private static long platformId;

    @LocalServerPort private int port;

    @Autowired private jakarta.servlet.ServletContext servletContext;

    @DynamicPropertySource
    static void oauthProperties(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("ptmcp.authz.oauth.enabled", () -> "true");
        registry.add("ptmcp.authz.oauth.issuer", () -> ISSUER);
        registry.add("ptmcp.authz.oauth.audience", () -> AUDIENCE);
        registry.add("ptmcp.authz.oauth.signing-key-file", () -> SIGNING_KEY.toString());
        registry.add("server.servlet.session.cookie.secure", () -> "true");
    }

    @AfterAll
    static void stopOwnedContainer() {
        if (MYSQL != null) {
            MYSQL.stop();
        }
    }

    @BeforeEach
    void clearAuthorizationState() throws SQLException {
        try (Connection connection = db()) {
            execute(connection, "DELETE FROM oauth_refresh_token");
            execute(connection, "DELETE FROM oauth_refresh_family");
            execute(connection, "DELETE FROM oauth2_authorization_consent");
            execute(connection, "DELETE FROM oauth2_authorization");
        }
    }

    @Test
    void loginPageIsHardenedAndRequiresCsrf() {
        assertEquals(PORT, port);
        assertEquals(480, servletContext.getSessionTimeout());

        HttpSession session = new HttpSession();
        Response page = session.get("/login");
        assertEquals(200, page.status());
        assertTrue(page.contentType().startsWith("text/html"));
        assertEquals(
                OAuthPageController.CONTENT_SECURITY_POLICY, page.first("Content-Security-Policy"));
        assertEquals("DENY", page.first("X-Frame-Options"));
        assertEquals("no-referrer", page.first("Referrer-Policy"));
        assertTrue(page.first("Cache-Control").contains("no-store"));
        assertEquals("no-cache", page.first("Pragma"));
        assertTrue(page.body().contains("action=\"/login\""));
        assertTrue(page.body().contains("name=\"_csrf\""));

        Response withoutCsrf =
                session.postForm(
                        "/login",
                        form(Map.of("username", USERNAME, "password", PASSWORD)),
                        Map.of());
        assertEquals(403, withoutCsrf.status());

        Response wrongPassword =
                session.postForm(
                        "/login",
                        form(
                                Map.of(
                                        "username",
                                        USERNAME,
                                        "password",
                                        "wrong-" + PASSWORD,
                                        "_csrf",
                                        csrf(page.body()))),
                        Map.of());
        assertEquals(302, wrongPassword.status());
        assertTrue(wrongPassword.first("Location").contains("/login?error"));
        assertAnonymousAuthorizeIsSentToLogin(session);
    }

    @Test
    void fullCodeFlowIssuesVerifiableJwtAndConsumesCodeOnce() throws Exception {
        HttpSession session = login();
        String verifier = verifier();
        String state = state();
        String code =
                issueCode(
                        session,
                        CONFIDENTIAL_CLIENT_ID,
                        CONFIDENTIAL_REDIRECT,
                        SCOPES,
                        state,
                        verifier,
                        true);

        try (Connection connection = db()) {
            assertEquals(
                    1,
                    scalar(
                            connection,
                            "SELECT COUNT(*) FROM oauth2_authorization "
                                    + "WHERE authorization_code_hash = ? AND code_consumed_at IS NULL",
                            sha256(code)));
            assertEquals(
                    CONFIDENTIAL_REDIRECT,
                    text(connection, "SELECT redirect_uri FROM oauth2_authorization LIMIT 1"));
            assertEquals(
                    "S256",
                    text(connection, "SELECT pkce_method FROM oauth2_authorization LIMIT 1"));
            assertEquals(
                    1,
                    scalar(
                            connection,
                            "SELECT COUNT(*) FROM oauth2_authorization_consent "
                                    + "WHERE registered_client_id = ? AND principal_name = ?",
                            CONFIDENTIAL_CLIENT_ROW,
                            USERNAME));
        }

        Response token =
                exchange(
                        code,
                        verifier,
                        CONFIDENTIAL_REDIRECT,
                        CONFIDENTIAL_CLIENT_ID,
                        CLIENT_SECRET);
        assertEquals(200, token.status());
        assertTrue(token.contentType().startsWith("application/json"));
        JsonNode body = JSON.readTree(token.body());
        assertEquals("Bearer", body.get("token_type").asString());
        // Spring Authorization Server truncates expires_in against the response clock, so a
        // request that crosses a second boundary reports 599; the authoritative 600s TTL is
        // asserted below against the JWT exp-iat difference.
        int expiresIn = body.get("expires_in").asInt();
        assertTrue(expiresIn == 600 || expiresIn == 599, "unexpected expires_in: " + expiresIn);
        assertEquals(Set.of("mcp:query", "mcp:catalog"), csv(body.get("scope").asString()));
        assertFalse(body.has("refresh_token"), "refresh tokens are out of scope for this slice");
        String accessToken = body.get("access_token").asString();

        JsonNode jwk = jwks().get("keys").get(0);
        SignedJWT jwt = SignedJWT.parse(accessToken);
        assertTrue(jwt.verify(new RSASSAVerifier(publicKey(jwk))));
        assertEquals(jwk.get("kid").asString(), jwt.getHeader().getKeyID());
        assertEquals("RS256", jwt.getHeader().getAlgorithm().getName());
        JWTClaimsSet claims = jwt.getJWTClaimsSet();
        assertEquals(ISSUER, claims.getIssuer());
        assertEquals(Long.toString(USER_ID), claims.getSubject());
        assertEquals(List.of(AUDIENCE), claims.getAudience());
        assertEquals(CONFIDENTIAL_CLIENT_ID, claims.getStringClaim("client_id"));
        assertEquals(platformId, claims.getLongClaim("platform_id"));
        assertEquals(1L, claims.getLongClaim("auth_version"));
        assertNotNull(claims.getJWTID());
        assertNotNull(claims.getIssueTime());
        assertNotNull(claims.getNotBeforeTime());
        assertEquals(
                600L,
                Duration.between(
                                claims.getIssueTime().toInstant(),
                                claims.getExpirationTime().toInstant())
                        .toSeconds());
        String sid = claims.getStringClaim("sid");
        assertTrue(sid.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
        assertEquals(Set.of("mcp:query", "mcp:catalog"), scopeClaim(claims));

        try (Connection connection = db()) {
            assertEquals(
                    1,
                    scalar(
                            connection,
                            "SELECT COUNT(*) FROM oauth2_authorization WHERE family_id = ?",
                            sid));
            assertEquals(
                    1,
                    scalar(
                            connection,
                            "SELECT COUNT(*) FROM oauth2_authorization "
                                    + "WHERE authorization_code_hash = ? AND code_consumed_at IS NOT NULL",
                            sha256(code)));
            assertEquals(
                    0,
                    scalar(
                            connection,
                            "SELECT COUNT(*) FROM oauth2_authorization WHERE "
                                    + "authorization_code_value IS NOT NULL "
                                    + "OR access_token_value IS NOT NULL "
                                    + "OR oidc_id_token_value IS NOT NULL "
                                    + "OR refresh_token_value IS NOT NULL "
                                    + "OR user_code_value IS NOT NULL "
                                    + "OR device_code_value IS NOT NULL"));
            String attributes =
                    new String(
                            bytes(
                                    connection,
                                    "SELECT attributes FROM oauth2_authorization LIMIT 1"),
                            StandardCharsets.UTF_8);
            assertFalse(attributes.contains(code));
            assertFalse(attributes.contains(accessToken));
        }

        Response replay =
                exchange(
                        code,
                        verifier,
                        CONFIDENTIAL_REDIRECT,
                        CONFIDENTIAL_CLIENT_ID,
                        CLIENT_SECRET);
        assertOAuthError(replay, 400, "invalid_grant");

        // Consent survived the exchange, so the same scopes no longer show the consent page.
        String secondCode =
                issueCode(
                        session,
                        CONFIDENTIAL_CLIENT_ID,
                        CONFIDENTIAL_REDIRECT,
                        SCOPES,
                        state(),
                        verifier(),
                        false);
        assertFalse(secondCode.isBlank());
    }

    @Test
    void verifierMismatchIsRejectedWithoutConsumingTheCode() throws SQLException {
        HttpSession session = login();
        String verifier = verifier();
        String code =
                issueCode(
                        session,
                        CONFIDENTIAL_CLIENT_ID,
                        CONFIDENTIAL_REDIRECT,
                        SCOPES,
                        state(),
                        verifier,
                        true);

        Response token =
                exchange(
                        code,
                        verifier(),
                        CONFIDENTIAL_REDIRECT,
                        CONFIDENTIAL_CLIENT_ID,
                        CLIENT_SECRET);
        assertOAuthError(token, 400, "invalid_grant");
        try (Connection connection = db()) {
            assertEquals(
                    1,
                    scalar(
                            connection,
                            "SELECT COUNT(*) FROM oauth2_authorization "
                                    + "WHERE authorization_code_hash = ? AND code_consumed_at IS NULL",
                            sha256(code)));
        }
    }

    @Test
    void invalidAuthorizationRequestsFailClosedWithoutRedirect() {
        HttpSession session = login();
        String state = state();
        String verifier = verifier();

        Response missingState =
                session.get(
                        authorizeUrl(
                                CONFIDENTIAL_CLIENT_ID,
                                CONFIDENTIAL_REDIRECT,
                                SCOPES,
                                null,
                                verifier,
                                "S256",
                                AUDIENCE));
        assertAuthorizeRequestRejected(missingState);

        for (String redirect :
                List.of(
                        CONFIDENTIAL_REDIRECT + "x",
                        CONFIDENTIAL_REDIRECT.substring(0, CONFIDENTIAL_REDIRECT.length() - 1))) {
            Response wrongRedirect =
                    session.get(
                            authorizeUrl(
                                    CONFIDENTIAL_CLIENT_ID,
                                    redirect,
                                    SCOPES,
                                    state,
                                    verifier,
                                    "S256",
                                    AUDIENCE));
            assertAuthorizeRequestRejected(wrongRedirect);
        }

        Response missingResource =
                session.get(
                        authorizeUrl(
                                CONFIDENTIAL_CLIENT_ID,
                                CONFIDENTIAL_REDIRECT,
                                SCOPES,
                                state,
                                verifier,
                                "S256",
                                null));
        assertAuthorizeRequestRejected(missingResource);

        Response wrongResource =
                session.get(
                        authorizeUrl(
                                CONFIDENTIAL_CLIENT_ID,
                                CONFIDENTIAL_REDIRECT,
                                SCOPES,
                                state,
                                verifier,
                                "S256",
                                "other-resource"));
        assertAuthorizeRequestRejected(wrongResource);
    }

    @Test
    void plainPkceIsRejectedWithOAuthErrorRedirect() {
        HttpSession session = login();
        String verifier = verifier();
        String state = state();
        Response response =
                session.get(
                        authorizeUrl(
                                CONFIDENTIAL_CLIENT_ID,
                                CONFIDENTIAL_REDIRECT,
                                SCOPES,
                                state,
                                verifier,
                                "plain",
                                AUDIENCE));
        assertEquals(302, response.status());
        String location = response.first("Location");
        assertTrue(location.startsWith(CONFIDENTIAL_REDIRECT), location);
        Map<String, List<String>> parameters = query(location);
        assertEquals(List.of(state), parameters.get("state"));
        assertEquals("invalid_request", first(parameters, "error"));
        assertFalse(parameters.containsKey("code"));
    }

    @Test
    void anonymousAuthorizeIsSentToLoginThenWorksAfterLogin() {
        HttpSession anonymous = new HttpSession();
        assertAnonymousAuthorizeIsSentToLogin(anonymous);

        HttpSession session = login();
        String code =
                issueCode(
                        session,
                        CONFIDENTIAL_CLIENT_ID,
                        CONFIDENTIAL_REDIRECT,
                        SCOPES,
                        state(),
                        verifier(),
                        true);
        assertFalse(code.isBlank());
    }

    @Test
    void unsupportedGrantsAndResponseTypesAreRejected() {
        HttpSession anonymous = new HttpSession();
        String authorize =
                authorizeUrl(
                        CONFIDENTIAL_CLIENT_ID,
                        CONFIDENTIAL_REDIRECT,
                        SCOPES,
                        state(),
                        verifier(),
                        "S256",
                        AUDIENCE);
        Response implicit =
                anonymous.get(authorize.replace("response_type=code", "response_type=token"));
        assertAuthorizeRequestRejected(implicit);

        Response password =
                new HttpSession()
                        .postForm(
                                "/oauth2/token",
                                form(
                                        Map.of(
                                                "grant_type", "password",
                                                "username", USERNAME,
                                                "password", PASSWORD,
                                                "client_id", CONFIDENTIAL_CLIENT_ID)),
                                Map.of(
                                        "Authorization",
                                        basic(CONFIDENTIAL_CLIENT_ID, CLIENT_SECRET)));
        assertOAuthError(password, 400, "unsupported_grant_type");
    }

    @Test
    void publicClientReceivesNoRefreshToken() throws Exception {
        HttpSession session = login();
        String verifier = verifier();
        String code =
                issueCode(
                        session,
                        PUBLIC_CLIENT_ID,
                        PUBLIC_REDIRECT,
                        PUBLIC_SCOPES,
                        state(),
                        verifier,
                        true);

        Response token = exchange(code, verifier, PUBLIC_REDIRECT, PUBLIC_CLIENT_ID, null);
        assertEquals(200, token.status());
        JsonNode body = JSON.readTree(token.body());
        assertFalse(body.has("refresh_token"));
        assertEquals("Bearer", body.get("token_type").asString());
        SignedJWT jwt = SignedJWT.parse(body.get("access_token").asString());
        assertTrue(jwt.verify(new RSASSAVerifier(publicKey(jwks().get("keys").get(0)))));
        assertEquals(PUBLIC_CLIENT_ID, jwt.getJWTClaimsSet().getStringClaim("client_id"));
        assertEquals(Long.toString(USER_ID), jwt.getJWTClaimsSet().getSubject());
    }

    @Test
    void publicClientRefreshRotatesAndReplayRevokesFamily() throws Exception {
        HttpSession session = login();
        String verifier = verifier();
        String code =
                issueCode(
                        session,
                        REFRESH_CLIENT_ID,
                        REFRESH_REDIRECT,
                        PUBLIC_SCOPES,
                        state(),
                        verifier,
                        true);

        Response token = exchange(code, verifier, REFRESH_REDIRECT, REFRESH_CLIENT_ID, null);
        assertEquals(200, token.status(), token.body());
        JsonNode body = JSON.readTree(token.body());
        String refresh = body.get("refresh_token").asString();
        assertFalse(refresh.isBlank());
        String familyId =
                SignedJWT.parse(body.get("access_token").asString())
                        .getJWTClaimsSet()
                        .getStringClaim("sid");
        assertNotNull(familyId);
        try (Connection connection = db()) {
            assertEquals(
                    1,
                    scalar(
                            connection,
                            "SELECT COUNT(*) FROM oauth_refresh_family "
                                    + "WHERE id = ? AND status = 'ACTIVE'",
                            familyId));
            assertEquals(
                    1,
                    scalar(
                            connection,
                            "SELECT COUNT(*) FROM oauth_refresh_token "
                                    + "WHERE family_id = ? AND generation = 1 AND status = 'ACTIVE'",
                            familyId));
        }

        Response refreshed =
                new HttpSession()
                        .postForm(
                                "/oauth2/token",
                                form(
                                        Map.of(
                                                "grant_type", "refresh_token",
                                                "refresh_token", refresh,
                                                "client_id", REFRESH_CLIENT_ID)),
                                Map.of());
        assertEquals(200, refreshed.status(), refreshed.body());
        JsonNode next = JSON.readTree(refreshed.body());
        String nextRefresh = next.get("refresh_token").asString();
        assertNotEquals(refresh, nextRefresh);
        assertEquals(
                familyId,
                SignedJWT.parse(next.get("access_token").asString())
                        .getJWTClaimsSet()
                        .getStringClaim("sid"));
        try (Connection connection = db()) {
            assertEquals(
                    1,
                    scalar(
                            connection,
                            "SELECT COUNT(*) FROM oauth_refresh_token "
                                    + "WHERE family_id = ? AND generation = 1 AND status = 'CONSUMED'",
                            familyId));
            assertEquals(
                    1,
                    scalar(
                            connection,
                            "SELECT COUNT(*) FROM oauth_refresh_token "
                                    + "WHERE family_id = ? AND generation = 2 AND status = 'ACTIVE'",
                            familyId));
        }

        Response replay =
                new HttpSession()
                        .postForm(
                                "/oauth2/token",
                                form(
                                        Map.of(
                                                "grant_type", "refresh_token",
                                                "refresh_token", refresh,
                                                "client_id", REFRESH_CLIENT_ID)),
                                Map.of());
        assertOAuthError(replay, 400, "invalid_grant");
        try (Connection connection = db()) {
            assertEquals(
                    0,
                    scalar(
                            connection,
                            "SELECT COUNT(*) FROM oauth_refresh_family "
                                    + "WHERE id = ? AND status = 'ACTIVE'",
                            familyId));
            assertEquals(
                    0,
                    scalar(
                            connection,
                            "SELECT COUNT(*) FROM oauth_refresh_token "
                                    + "WHERE family_id = ? AND status <> 'REVOKED'",
                            familyId));
        }
    }

    @Test
    void publicClientRevokesItsRefreshFamilyWithoutLeakingOtherClientTokens() throws Exception {
        HttpSession session = login();
        String verifier = verifier();
        String code =
                issueCode(
                        session,
                        REFRESH_CLIENT_ID,
                        REFRESH_REDIRECT,
                        PUBLIC_SCOPES,
                        state(),
                        verifier,
                        true);
        Response token = exchange(code, verifier, REFRESH_REDIRECT, REFRESH_CLIENT_ID, null);
        assertEquals(200, token.status(), token.body());
        JsonNode body = JSON.readTree(token.body());
        String refresh = body.get("refresh_token").asString();
        String familyId =
                SignedJWT.parse(body.get("access_token").asString())
                        .getJWTClaimsSet()
                        .getStringClaim("sid");

        Response foreign =
                new HttpSession()
                        .postForm(
                                "/oauth2/revoke",
                                form(
                                        Map.of(
                                                "token", refresh,
                                                "client_id", PUBLIC_CLIENT_ID)),
                                Map.of());
        assertEquals(200, foreign.status(), foreign.body());
        try (Connection connection = db()) {
            assertEquals(
                    1,
                    scalar(
                            connection,
                            "SELECT COUNT(*) FROM oauth_refresh_family "
                                    + "WHERE id = ? AND status = 'ACTIVE'",
                            familyId));
        }

        Response revoked =
                new HttpSession()
                        .postForm(
                                "/oauth2/revoke",
                                form(
                                        Map.of(
                                                "token", refresh,
                                                "client_id", REFRESH_CLIENT_ID)),
                                Map.of());
        assertEquals(200, revoked.status(), revoked.body());
        try (Connection connection = db()) {
            assertEquals(
                    0,
                    scalar(
                            connection,
                            "SELECT COUNT(*) FROM oauth_refresh_family "
                                    + "WHERE id = ? AND status = 'ACTIVE'",
                            familyId));
            assertEquals(
                    0,
                    scalar(
                            connection,
                            "SELECT COUNT(*) FROM oauth_refresh_token "
                                    + "WHERE family_id = ? AND status <> 'REVOKED'",
                            familyId));
        }

        Response refreshAfterRevoke =
                new HttpSession()
                        .postForm(
                                "/oauth2/token",
                                form(
                                        Map.of(
                                                "grant_type", "refresh_token",
                                                "refresh_token", refresh,
                                                "client_id", REFRESH_CLIENT_ID)),
                                Map.of());
        assertOAuthError(refreshAfterRevoke, 400, "invalid_grant");
    }

    @Test
    void discoveryAdvertisesOnlyTheImplementedSurface() throws Exception {
        Response metadata = new HttpSession().get("/.well-known/oauth-authorization-server");
        assertEquals(200, metadata.status());
        assertTrue(metadata.contentType().startsWith("application/json"));
        JsonNode document = JSON.readTree(metadata.body());
        assertEquals(ISSUER, document.get("issuer").asString());
        assertEquals(
                ISSUER + "/oauth2/authorize", document.get("authorization_endpoint").asString());
        assertEquals(ISSUER + "/oauth2/token", document.get("token_endpoint").asString());
        assertEquals(ISSUER + "/oauth2/jwks", document.get("jwks_uri").asString());
        assertEquals(2, document.get("grant_types_supported").size());
        Set<String> grants = new HashSet<>();
        document.get("grant_types_supported").forEach(node -> grants.add(node.asString()));
        assertEquals(Set.of("authorization_code", "refresh_token"), grants);
        Set<String> methods = new HashSet<>();
        document.get("token_endpoint_auth_methods_supported")
                .forEach(node -> methods.add(node.asString()));
        assertEquals(Set.of("client_secret_basic", "client_secret_post", "none"), methods);
        assertEquals(ISSUER + "/oauth2/revoke", document.get("revocation_endpoint").asString());
        Set<String> revocationMethods = new HashSet<>();
        document.get("revocation_endpoint_auth_methods_supported")
                .forEach(node -> revocationMethods.add(node.asString()));
        assertTrue(revocationMethods.contains("none"));
        for (String claim :
                List.of(
                        "introspection_endpoint",
                        "introspection_endpoint_auth_methods_supported",
                        "registration_endpoint",
                        "pushed_authorization_request_endpoint",
                        "device_authorization_endpoint",
                        "tls_client_certificate_bound_access_tokens",
                        "dpop_signing_alg_values_supported")) {
            assertFalse(document.has(claim), claim);
        }

        Response jwks = new HttpSession().get("/oauth2/jwks");
        assertEquals(200, jwks.status());
        JsonNode keys = JSON.readTree(jwks.body()).get("keys");
        assertEquals(1, keys.size());
        JsonNode key = keys.get(0);
        assertEquals(
                Set.of("kty", "kid", "use", "alg", "n", "e"), new HashSet<>(key.propertyNames()));
        assertTrue(key.get("kid").asString().length() >= 20);
    }

    @Test
    void unimplementedOAuthEndpointsAreClosed() {
        for (String path :
                List.of(
                        "/oauth2/introspect",
                        "/oauth2/par",
                        "/oauth2/device_authorization",
                        "/oauth2/device_verification",
                        "/connect/register",
                        "/userinfo",
                        "/connect/logout")) {
            assertEquals(403, new HttpSession().get(path).status(), path);
        }
    }

    @Test
    void consentPageEscapesMetadataAndDenyDoesNotIssueACode() {
        HttpSession session = login();
        Response page =
                session.get(
                        "/oauth2/consent?client_id="
                                + ESCAPE_CLIENT_ID
                                + "&scope=mcp:query&state=page-state");
        assertEquals(200, page.status());
        assertTrue(page.body().contains("&lt;script&gt;"));
        assertFalse(page.body().contains("<script>"));
        assertEquals(
                OAuthPageController.CONTENT_SECURITY_POLICY, page.first("Content-Security-Policy"));
        assertTrue(page.body().contains("action=\"/oauth2/authorize\""));
        assertTrue(page.first("Cache-Control").contains("no-store"));

        assertEquals(400, session.get("/oauth2/consent?client_id=" + ESCAPE_CLIENT_ID).status());
        assertEquals(
                400,
                session.get("/oauth2/consent?client_id=unknown-client&state=page-state").status());

        String state = state();
        Response begin =
                session.get(
                        authorizeUrl(
                                ESCAPE_CLIENT_ID,
                                ESCAPE_REDIRECT,
                                PUBLIC_SCOPES,
                                state,
                                verifier(),
                                "S256",
                                AUDIENCE));
        assertEquals(302, begin.status());
        assertTrue(begin.first("Location").contains("/oauth2/consent"));
        Response consentPage = session.get(begin.first("Location"));
        assertEquals(200, consentPage.status());
        String consentState = first(query(begin.first("Location")), "state");
        assertNotNull(consentState);
        Response denied =
                session.postForm(
                        "/oauth2/authorize",
                        form(
                                Map.of(
                                        "client_id", ESCAPE_CLIENT_ID,
                                        "state", consentState,
                                        "_csrf", csrf(consentPage.body()))),
                        Map.of());
        assertEquals(302, denied.status());
        String location = denied.first("Location");
        assertTrue(location.startsWith(ESCAPE_REDIRECT), location);
        Map<String, List<String>> parameters = query(location);
        assertEquals("access_denied", first(parameters, "error"));
        assertEquals(List.of(state), parameters.get("state"));
        assertFalse(parameters.containsKey("code"));
    }

    private void assertAnonymousAuthorizeIsSentToLogin(HttpSession session) {
        Response response =
                session.get(
                        authorizeUrl(
                                CONFIDENTIAL_CLIENT_ID,
                                CONFIDENTIAL_REDIRECT,
                                SCOPES,
                                state(),
                                verifier(),
                                "S256",
                                AUDIENCE));
        assertEquals(302, response.status());
        assertEquals("/login", URI.create(response.first("Location")).getPath());
        assertFalse(response.first("Location").contains("error"));
    }

    private HttpSession login() {
        HttpSession session = new HttpSession();
        Response page = session.get("/login");
        assertEquals(200, page.status());
        Response response =
                session.postForm(
                        "/login",
                        form(
                                Map.of(
                                        "username", USERNAME,
                                        "password", PASSWORD,
                                        "_csrf", csrf(page.body()))),
                        Map.of());
        assertEquals(302, response.status());
        assertFalse(response.first("Location").contains("error"));
        String cookie =
                response.all("Set-Cookie").stream()
                        .filter(
                                value ->
                                        value.startsWith(
                                                OAuthSecurityConfiguration.SESSION_COOKIE + "="))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("AS session cookie missing"));
        String attributes = cookie.toLowerCase(Locale.ROOT);
        assertTrue(attributes.contains("httponly"), cookie);
        assertTrue(attributes.contains("secure"), cookie);
        assertTrue(attributes.contains("samesite=lax"), cookie);
        assertTrue(session.cookies.containsKey(OAuthSecurityConfiguration.SESSION_COOKIE));
        return session;
    }

    private String issueCode(
            HttpSession session,
            String clientId,
            String redirectUri,
            String scopes,
            String state,
            String verifier,
            boolean expectConsent) {
        Response begin =
                session.get(
                        authorizeUrl(
                                clientId, redirectUri, scopes, state, verifier, "S256", AUDIENCE));
        assertEquals(302, begin.status());
        String location = begin.first("Location");
        if (expectConsent) {
            assertTrue(location.contains("/oauth2/consent"), location);
            Map<String, List<String>> consentRedirect = query(location);
            String consentState = first(consentRedirect, "state");
            assertNotNull(consentState);
            assertEquals(List.of(clientId), consentRedirect.get("client_id"));
            Response page = session.get(location);
            assertEquals(200, page.status());
            assertTrue(page.body().contains("name=\"state\" value=\"" + consentState + "\""));
            Response allowed =
                    session.postForm(
                            "/oauth2/authorize",
                            consentForm(clientId, consentState, scopes, csrf(page.body())),
                            Map.of());
            assertEquals(302, allowed.status());
            location = allowed.first("Location");
        }
        assertTrue(location.startsWith(redirectUri), location);
        Map<String, List<String>> parameters = query(location);
        assertEquals(List.of(state), parameters.get("state"));
        assertFalse(parameters.containsKey("error"));
        List<String> codes = parameters.get("code");
        assertNotNull(codes);
        assertEquals(1, codes.size());
        return codes.getFirst();
    }

    private Response exchange(
            String code, String verifier, String redirectUri, String clientId, String secret) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (secret != null) {
            headers.put("Authorization", basic(clientId, secret));
        }
        return new HttpSession()
                .postForm(
                        "/oauth2/token",
                        form(
                                Map.of(
                                        "grant_type", "authorization_code",
                                        "code", code,
                                        "redirect_uri", redirectUri,
                                        "code_verifier", verifier,
                                        "client_id", clientId)),
                        headers);
    }

    private static void assertAuthorizeRequestRejected(Response response) {
        // SAS sends sendError(400) when it cannot build an error redirect; the fail-closed
        // contract is no Location, no code, and no client redirect echo in the response.
        assertEquals(400, response.status());
        assertEquals("", response.first("Location"));
        assertFalse(response.body().contains("code="));
        assertFalse(response.body().contains(CONFIDENTIAL_REDIRECT));
    }

    private static void assertOAuthError(Response response, int status, String error) {
        assertEquals(status, response.status());
        assertTrue(
                response.contentType().startsWith("application/json"),
                response.contentType() + " body=" + response.body());
        assertFalse(response.headers().firstValue("Location").isPresent());
        JsonNode body = JSON.readTree(response.body());
        assertEquals(error, body.get("error").asString());
        for (String field : List.of("code", "message", "data", "timestamp")) {
            assertFalse(body.has(field), field);
        }
    }

    private static String authorizeUrl(
            String clientId,
            String redirectUri,
            String scopes,
            String state,
            String verifier,
            String method,
            String resource) {
        StringBuilder url =
                new StringBuilder("/oauth2/authorize?response_type=code&client_id=")
                        .append(encode(clientId))
                        .append("&redirect_uri=")
                        .append(encode(redirectUri))
                        .append("&scope=")
                        .append(encode(scopes));
        if (state != null) {
            url.append("&state=").append(encode(state));
        }
        String challenge = "S256".equals(method) ? challenge(verifier) : verifier;
        url.append("&code_challenge=")
                .append(encode(challenge))
                .append("&code_challenge_method=")
                .append(encode(method));
        if (resource != null) {
            url.append("&resource=").append(encode(resource));
        }
        return url.toString();
    }

    private static String consentForm(String clientId, String state, String scopes, String csrf) {
        StringBuilder body =
                new StringBuilder("client_id=")
                        .append(encode(clientId))
                        .append("&state=")
                        .append(encode(state))
                        .append("&_csrf=")
                        .append(encode(csrf));
        for (String scope : scopes.split(" ")) {
            body.append("&scope=").append(encode(scope));
        }
        return body.toString();
    }

    private static JsonNode jwks() {
        Response response = new HttpSession().get("/oauth2/jwks");
        assertEquals(200, response.status());
        return JSON.readTree(response.body());
    }

    private static RSAPublicKey publicKey(JsonNode jwk) throws Exception {
        BigInteger modulus =
                new BigInteger(1, Base64.getUrlDecoder().decode(jwk.get("n").asString()));
        BigInteger exponent =
                new BigInteger(1, Base64.getUrlDecoder().decode(jwk.get("e").asString()));
        return (RSAPublicKey)
                KeyFactory.getInstance("RSA")
                        .generatePublic(new RSAPublicKeySpec(modulus, exponent));
    }

    private static MySQLContainer startMysql() {
        MySQLContainer container =
                new MySQLContainer(MYSQL_IMAGE)
                        .withDatabaseName("oauth_flow_test")
                        .withUsername("oauth_flow")
                        .withPassword(randomToken(24))
                        .withEnv("TZ", "UTC")
                        .withCommand(
                                "--default-time-zone=+00:00",
                                "--character-set-server=utf8mb4",
                                "--collation-server=utf8mb4_0900_bin")
                        .withUrlParam("connectionTimeZone", "UTC")
                        .withUrlParam("forceConnectionTimeZoneToSession", "true")
                        .withUrlParam("allowPublicKeyRetrieval", "true")
                        .withUrlParam("useSSL", "false");
        try {
            container.start();
            Flyway.configure()
                    .dataSource(
                            container.getJdbcUrl(),
                            container.getUsername(),
                            container.getPassword())
                    .locations("classpath:db/ptmcp-migration", "classpath:db/authz-test-migration")
                    .initSql("SET time_zone = '+00:00'")
                    .cleanDisabled(true)
                    .baselineOnMigrate(false)
                    .load()
                    .migrate();
            seed(container);
            return container;
        } catch (RuntimeException | Error failure) {
            container.stop();
            throw failure;
        }
    }

    private static void seed(MySQLContainer container) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
        try (Connection connection =
                connect(container.getJdbcUrl(), container.getUsername(), container.getPassword())) {
            platformId =
                    insert(
                            connection,
                            "INSERT INTO platform_registry (code, name, tier, status, catalog_mode, "
                                    + "proof_mode, scope_version, security_version, create_time, "
                                    + "update_time, created_by, updated_by) "
                                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            "OAUTH_FLOW",
                            "OAuth flow platform",
                            2,
                            "ACTIVE",
                            "DISCOVER",
                            "NONE",
                            1,
                            1,
                            now,
                            now,
                            1,
                            1);
            execute(
                    connection,
                    "INSERT INTO sys_user (user_id, user_name, nick_name, password, status, del_flag) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    USER_ID,
                    USERNAME,
                    "Alice",
                    new BCryptPasswordEncoder().encode(PASSWORD),
                    "0",
                    "0");
            execute(
                    connection,
                    "INSERT INTO sys_user_login_name (user_id, normalized_name, create_time, "
                            + "update_time) VALUES (?, ?, ?, ?)",
                    USER_ID,
                    USERNAME,
                    now,
                    now);
            insertClient(
                    connection,
                    CONFIDENTIAL_CLIENT_ROW,
                    CONFIDENTIAL_CLIENT_ID,
                    "{bcrypt}" + new BCryptPasswordEncoder().encode(CLIENT_SECRET),
                    "Confidential client",
                    "client_secret_basic,client_secret_post",
                    CONFIDENTIAL_REDIRECT,
                    CLIENT_SCOPES,
                    "CONFIDENTIAL",
                    now);
            insertClient(
                    connection,
                    PUBLIC_CLIENT_ROW,
                    PUBLIC_CLIENT_ID,
                    null,
                    "Public desktop client",
                    "none",
                    PUBLIC_REDIRECT,
                    PUBLIC_CLIENT_SCOPES,
                    "PUBLIC",
                    now);
            insertClient(
                    connection,
                    ESCAPE_CLIENT_ROW,
                    ESCAPE_CLIENT_ID,
                    null,
                    "<script>alert(1)</script>",
                    "none",
                    ESCAPE_REDIRECT,
                    PUBLIC_CLIENT_SCOPES,
                    "PUBLIC",
                    now);
            insertClient(
                    connection,
                    REFRESH_CLIENT_ROW,
                    REFRESH_CLIENT_ID,
                    null,
                    "Public refresh client",
                    "none",
                    REFRESH_REDIRECT,
                    PUBLIC_CLIENT_SCOPES,
                    "PUBLIC",
                    now,
                    "authorization_code,refresh_token");
        } catch (SQLException failure) {
            throw new IllegalStateException("Unable to seed OAuth test data", failure);
        }
    }

    private static void insertClient(
            Connection connection,
            String id,
            String clientId,
            String secret,
            String name,
            String methods,
            String redirectUri,
            String scopes,
            String type,
            LocalDateTime now)
            throws SQLException {
        insertClient(
                connection,
                id,
                clientId,
                secret,
                name,
                methods,
                redirectUri,
                scopes,
                type,
                now,
                "authorization_code");
    }

    private static void insertClient(
            Connection connection,
            String id,
            String clientId,
            String secret,
            String name,
            String methods,
            String redirectUri,
            String scopes,
            String type,
            LocalDateTime now,
            String grants)
            throws SQLException {
        execute(
                connection,
                "INSERT INTO oauth2_registered_client (id, client_id, client_secret, client_name, "
                        + "client_authentication_methods, authorization_grant_types, redirect_uris, "
                        + "scopes, client_settings, token_settings, platform_id, client_type, "
                        + "status, registration_source, version, create_time, update_time, "
                        + "created_by, updated_by) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id,
                clientId,
                secret,
                name,
                methods,
                grants,
                redirectUri,
                scopes,
                "{\"settings.client.require-authorization-consent\":true}",
                "{}",
                platformId,
                type,
                "ACTIVE",
                "ADMIN",
                1,
                now,
                now,
                1,
                1);
    }

    private static Path generateSigningKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            byte[] encoded = generator.generateKeyPair().getPrivate().getEncoded();
            String pem =
                    "-----BEGIN PRIVATE KEY-----\n"
                            + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                                    .encodeToString(encoded)
                            + "\n-----END PRIVATE KEY-----\n";
            Path file = Files.createTempFile("ptmcp-as-signing-", ".pem");
            Files.writeString(file, pem, StandardCharsets.US_ASCII);
            file.toFile().deleteOnExit();
            return file;
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to create the OAuth signing key", failure);
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (Exception failure) {
            throw new IllegalStateException("Unable to reserve a test port", failure);
        }
    }

    private static Connection db() throws SQLException {
        return connect(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private static Connection connect(String url, String username, String password)
            throws SQLException {
        // Docker Desktop port forwards on Windows occasionally drop the first SYN;
        // retry a couple of times before giving up.
        SQLException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return DriverManager.getConnection(url, username, password);
            } catch (SQLException failure) {
                last = failure;
                try {
                    Thread.sleep(2000L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw failure;
                }
            }
        }
        throw last;
    }

    private static long scalar(Connection connection, String sql, Object... values)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getLong(1);
            }
        }
    }

    private static String text(Connection connection, String sql, Object... values)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }

    private static byte[] bytes(Connection connection, String sql, Object... values)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getBytes(1);
            }
        }
    }

    private static long insert(Connection connection, String sql, Object... values)
            throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(statement, values);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertTrue(keys.next());
                return keys.getLong(1);
            }
        }
    }

    private static void execute(Connection connection, String sql, Object... values)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(statement, values);
            statement.executeUpdate();
        }
    }

    private static void bind(PreparedStatement statement, Object[] values) throws SQLException {
        for (int index = 0; index < values.length; index++) {
            statement.setObject(index + 1, values[index]);
        }
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String verifier() {
        return randomToken(48);
    }

    private static String challenge(String verifier) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(verifier));
    }

    private static String state() {
        return randomToken(24);
    }

    private static String randomToken(int bytes) {
        byte[] value = new byte[bytes];
        new SecureRandom().nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static String basic(String clientId, String secret) {
        return "Basic "
                + Base64.getEncoder()
                        .encodeToString((clientId + ":" + secret).getBytes(StandardCharsets.UTF_8));
    }

    private static String form(Map<String, String> values) {
        return values.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String csrf(String html) {
        Matcher matcher = CSRF_VALUE.matcher(html);
        assertTrue(matcher.find(), "CSRF token missing");
        return matcher.group(1);
    }

    private static Map<String, List<String>> query(String location) {
        Map<String, List<String>> parameters = new LinkedHashMap<>();
        String raw = URI.create(location).getRawQuery();
        if (raw == null || raw.isBlank()) {
            return parameters;
        }
        for (String pair : raw.split("&")) {
            int separator = pair.indexOf('=');
            String name =
                    URLDecoder.decode(
                            separator < 0 ? pair : pair.substring(0, separator),
                            StandardCharsets.UTF_8);
            String value =
                    separator < 0
                            ? ""
                            : URLDecoder.decode(
                                    pair.substring(separator + 1), StandardCharsets.UTF_8);
            parameters.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
        }
        return parameters;
    }

    private static String first(Map<String, List<String>> parameters, String name) {
        List<String> values = parameters.get(name);
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    private static Set<String> csv(String value) {
        return value == null
                ? Set.of()
                : Arrays.stream(value.split(" ")).collect(Collectors.toSet());
    }

    private static Set<String> scopeClaim(JWTClaimsSet claims) {
        Object value = claims.getClaim("scope");
        if (value instanceof Collection<?> values) {
            return values.stream().map(String.class::cast).collect(Collectors.toSet());
        }
        return value instanceof String text ? csv(text) : Set.of();
    }

    private record Response(int status, HttpHeaders headers, String body) {
        String first(String name) {
            return headers.firstValue(name).orElse("");
        }

        List<String> all(String name) {
            return headers.allValues(name);
        }

        String contentType() {
            return first("Content-Type");
        }
    }

    private static final class HttpSession {
        private final Map<String, String> cookies = new LinkedHashMap<>();

        Response get(String path) {
            return send("GET", path, null, Map.of());
        }

        Response postForm(String path, String body, Map<String, String> headers) {
            return send("POST", path, body, headers);
        }

        private Response send(
                String method, String path, String body, Map<String, String> headers) {
            URI uri =
                    URI.create(
                            path.startsWith("http://") || path.startsWith("https://")
                                    ? path
                                    : ISSUER + path);
            HttpRequest.Builder builder =
                    HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20));
            Map<String, String> requested = new LinkedHashMap<>(headers);
            if (!path.startsWith("http")) {
                requested.putIfAbsent("Accept", "application/json, text/html;q=0.9");
            }
            if (!cookies.isEmpty()) {
                builder.header("Cookie", cookieHeader());
            }
            requested.forEach(builder::header);
            if ("GET".equals(method)) {
                builder.GET();
            } else {
                builder.header("Content-Type", "application/x-www-form-urlencoded");
                builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            }
            HttpResponse<String> response;
            try {
                response =
                        HTTP.send(
                                builder.build(),
                                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (Exception failure) {
                throw new IllegalStateException(method + " " + uri + " failed", failure);
            }
            store(response.headers());
            return new Response(response.statusCode(), response.headers(), response.body());
        }

        private String cookieHeader() {
            return cookies.entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .collect(Collectors.joining("; "));
        }

        private void store(HttpHeaders headers) {
            for (String header : headers.allValues("Set-Cookie")) {
                String pair = header.split(";", 2)[0];
                int separator = pair.indexOf('=');
                if (separator <= 0) {
                    continue;
                }
                String name = pair.substring(0, separator).trim();
                String value = pair.substring(separator + 1).trim();
                if (header.toLowerCase(Locale.ROOT).contains("max-age=0")) {
                    cookies.remove(name);
                } else {
                    cookies.put(name, value);
                }
            }
        }
    }
}
