package local.ptmcp.authz.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Serves the configured React bundle on the AS origin. The controller only returns files; the
 * security-relevant part is that pages keep the no-store/deny headers and that the asset handler
 * cannot be walked out of its directory.
 */
@WebMvcTest(controllers = OAuthPageController.class, properties = "ptmcp.authz.oauth.enabled=true")
@AutoConfigureMockMvc(addFilters = false)
@Import({OAuthPages.class, OAuthPageAssetsConfiguration.class})
class OAuthReactPagesTest {
    private static final String LOGIN_DOCUMENT =
            "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                    + "<link rel=\"stylesheet\" href=\"/oauth/assets/app.css\"></head>"
                    + "<body><div id=\"root\"></div>"
                    + "<script type=\"module\" src=\"/oauth/assets/app.js\"></script></body></html>";
    private static final String CONSENT_DOCUMENT =
            "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                    + "<link rel=\"stylesheet\" href=\"/oauth/assets/app.css\"></head>"
                    + "<body><div id=\"root\"></div>"
                    + "<script type=\"module\" src=\"/oauth/assets/app.js\"></script></body></html>";
    private static final String ASSET = "body{margin:0}";
    private static final String SECRET = "host secret";

    private static final Path PAGES = createBundle();

    @Autowired private MockMvc mvc;

    @MockitoBean private PtmcpRegisteredClientRepository clients;

    @MockitoBean private OAuth2AuthorizationService authorizations;

    @MockitoBean private OAuth2AuthorizationConsentService consents;

    @MockitoBean private AuthorizationServerSettings settings;

    @DynamicPropertySource
    static void pagesDirectory(DynamicPropertyRegistry registry) {
        registry.add("ptmcp.authz.oauth.pages-directory", () -> PAGES.toString());
    }

    @AfterAll
    static void removeBundle() throws IOException {
        try (Stream<Path> paths = Files.walk(PAGES)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(
                            path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException exception) {
                                    throw new UncheckedIOException(exception);
                                }
                            });
        }
    }

    @Test
    void loginPageIsTheConfiguredBundleWithHardenedHeaders() throws Exception {
        MvcResult result = mvc.perform(get("/login")).andReturn();

        assertEquals(200, result.getResponse().getStatus());
        assertEquals(LOGIN_DOCUMENT, result.getResponse().getContentAsString());
        assertPageHeaders(result);
    }

    @Test
    void consentPageIsTheConfiguredBundleWithHardenedHeaders() throws Exception {
        MvcResult result = mvc.perform(get("/oauth2/consent")).andReturn();

        assertEquals(200, result.getResponse().getStatus());
        assertEquals(CONSENT_DOCUMENT, result.getResponse().getContentAsString());
        assertPageHeaders(result);
    }

    @Test
    void assetsAreServedWithImmutableCaching() throws Exception {
        MvcResult result = mvc.perform(get("/oauth/assets/app.css")).andReturn();

        assertEquals(200, result.getResponse().getStatus());
        assertEquals(ASSET, result.getResponse().getContentAsString());
        String cacheControl = result.getResponse().getHeader("Cache-Control");
        assertTrue(cacheControl.contains("max-age=31536000"), cacheControl);
        assertTrue(cacheControl.contains("public"), cacheControl);
        assertTrue(cacheControl.contains("immutable"), cacheControl);
    }

    @Test
    void missingAssetIsNotFound() throws Exception {
        assertEquals(
                404,
                mvc.perform(get("/oauth/assets/missing.css"))
                        .andReturn()
                        .getResponse()
                        .getStatus());
    }

    @Test
    void assetHandlerCannotWalkOutOfItsDirectory() throws Exception {
        for (String path :
                new String[] {
                    "/oauth/assets/../secret.txt",
                    "/oauth/assets/%2e%2e/secret.txt",
                    "/oauth/assets/..%2fsecret.txt",
                    "/oauth/assets/../oauth/login.html"
                }) {
            MvcResult result = mvc.perform(get(path)).andReturn();
            assertEquals(404, result.getResponse().getStatus(), path);
            assertFalse(result.getResponse().getContentAsString().contains(SECRET), path);
        }
    }

    private static void assertPageHeaders(MvcResult result) {
        assertEquals("text/html;charset=UTF-8", result.getResponse().getContentType());
        assertTrue(
                result.getResponse().getHeader("Cache-Control").contains("no-store"),
                result.getResponse().getHeader("Cache-Control"));
        assertEquals("no-cache", result.getResponse().getHeader("Pragma"));
        assertEquals("DENY", result.getResponse().getHeader("X-Frame-Options"));
        assertEquals("no-referrer", result.getResponse().getHeader("Referrer-Policy"));
        assertEquals(
                OAuthPageController.REACT_CONTENT_SECURITY_POLICY,
                result.getResponse().getHeader("Content-Security-Policy"));
    }

    private static Path createBundle() {
        try {
            Path root = Files.createTempDirectory("ptmcp-oauth-pages");
            Path oauth = Files.createDirectories(root.resolve("oauth"));
            Path assets = Files.createDirectories(root.resolve("assets"));
            Files.writeString(oauth.resolve("login.html"), LOGIN_DOCUMENT, StandardCharsets.UTF_8);
            Files.writeString(
                    oauth.resolve("consent.html"), CONSENT_DOCUMENT, StandardCharsets.UTF_8);
            Files.writeString(assets.resolve("app.css"), ASSET, StandardCharsets.UTF_8);
            Files.writeString(root.resolve("secret.txt"), SECRET, StandardCharsets.UTF_8);
            return root;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
