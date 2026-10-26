package local.ptmcp.authz.oauth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Fail-closed AS baseline: every effective value is required before OAuth starts. */
@ConfigurationProperties("ptmcp.authz.oauth")
public record OAuthProperties(
        boolean enabled,
        String issuer,
        String audience,
        String signingKeyFile,
        Duration authorizationCodeTtl,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        Duration refreshFamilyAbsoluteTtl) {

    public OAuthProperties {
        if (issuer == null || issuer.isBlank()) {
            issuer = "http://127.0.0.1:8081";
        }
        if (audience == null || audience.isBlank()) {
            audience = "ptmcp-mcp";
        }
        if (authorizationCodeTtl == null) {
            authorizationCodeTtl = Duration.ofMinutes(5);
        }
        if (accessTokenTtl == null) {
            accessTokenTtl = Duration.ofMinutes(10);
        }
        if (refreshTokenTtl == null) {
            refreshTokenTtl = Duration.ofDays(7);
        }
        if (refreshFamilyAbsoluteTtl == null) {
            refreshFamilyAbsoluteTtl = Duration.ofDays(30);
        }
        if (enabled && (signingKeyFile == null || signingKeyFile.isBlank())) {
            throw new IllegalArgumentException(
                    "ptmcp.authz.oauth.signing-key-file is required when OAuth is enabled");
        }
        if (authorizationCodeTtl.isZero()
                || authorizationCodeTtl.isNegative()
                || accessTokenTtl.isZero()
                || accessTokenTtl.isNegative()
                || refreshTokenTtl.isZero()
                || refreshTokenTtl.isNegative()
                || refreshFamilyAbsoluteTtl.isZero()
                || refreshFamilyAbsoluteTtl.isNegative()
                || refreshTokenTtl.compareTo(refreshFamilyAbsoluteTtl) > 0) {
            throw new IllegalArgumentException("OAuth token lifetimes must be positive");
        }
    }
}
