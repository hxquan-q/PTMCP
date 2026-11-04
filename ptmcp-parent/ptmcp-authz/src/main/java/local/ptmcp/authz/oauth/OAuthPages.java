package local.ptmcp.authz.oauth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Optional React bundle for the AS-hosted pages. The frontend project builds it separately; when
 * the directory is configured every required file must exist, otherwise startup fails. Without a
 * directory the server-rendered fallback stays in use.
 */
@Component
@ConditionalOnProperty(prefix = "ptmcp.authz.oauth", name = "enabled", havingValue = "true")
public final class OAuthPages {
    private static final String LOGIN_FILE = "oauth/login.html";
    private static final String CONSENT_FILE = "oauth/consent.html";
    private static final String ASSETS_DIRECTORY = "assets";

    private final Path loginFile;
    private final Path consentFile;
    private final Path assets;

    public OAuthPages(@Value("${ptmcp.authz.oauth.pages-directory:}") String directory) {
        if (directory == null || directory.isBlank()) {
            this.loginFile = null;
            this.consentFile = null;
            this.assets = null;
            return;
        }
        Path root = Path.of(directory.trim()).toAbsolutePath().normalize();
        Path configuredLogin = root.resolve(LOGIN_FILE);
        Path configuredConsent = root.resolve(CONSENT_FILE);
        Path configuredAssets = root.resolve(ASSETS_DIRECTORY);
        if (!Files.isRegularFile(configuredLogin)
                || !Files.isRegularFile(configuredConsent)
                || !Files.isDirectory(configuredAssets)) {
            throw new IllegalStateException(
                    "ptmcp.authz.oauth.pages-directory must contain "
                            + LOGIN_FILE
                            + ", "
                            + CONSENT_FILE
                            + " and "
                            + ASSETS_DIRECTORY
                            + "/: "
                            + root);
        }
        this.loginFile = configuredLogin;
        this.consentFile = configuredConsent;
        this.assets = configuredAssets;
    }

    public boolean configured() {
        return loginFile != null;
    }

    public String loginHtml() {
        return read(loginFile);
    }

    public String consentHtml() {
        return read(consentFile);
    }

    public Optional<String> assetsLocation() {
        if (assets == null) {
            return Optional.empty();
        }
        String location = assets.toUri().toString();
        return Optional.of(location.endsWith("/") ? location : location + "/");
    }

    private static String read(Path file) {
        if (file == null) {
            throw new IllegalStateException("OAuth page bundle is not configured");
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("OAuth page bundle is unreadable: " + file, exception);
        }
    }
}
