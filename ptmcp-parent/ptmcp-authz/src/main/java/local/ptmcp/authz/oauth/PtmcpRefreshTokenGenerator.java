package local.ptmcp.authz.oauth;

import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;

/**
 * Mints opaque 256-bit refresh tokens when the registered client explicitly allows the refresh
 * grant. Spring's default generator rejects public clients; PTMCP's public-client extension is only
 * safe because the authorization service persists a hash, enforces generations and revokes the
 * whole family on replay. The value never contains identity or authorization claims.
 */
final class PtmcpRefreshTokenGenerator implements OAuth2TokenGenerator<OAuth2RefreshToken> {
    private static final int TOKEN_BYTES = 32;
    private final OAuthProperties properties;
    private final SecureRandom random = new SecureRandom();

    PtmcpRefreshTokenGenerator(OAuthProperties properties) {
        this.properties = properties;
    }

    @Override
    public OAuth2RefreshToken generate(OAuth2TokenContext context) {
        if (context == null
                || context.getTokenType() == null
                || !OAuth2ParameterNames.REFRESH_TOKEN.equals(context.getTokenType().getValue())) {
            return null;
        }
        RegisteredClient client = context.getRegisteredClient();
        if (client == null
                || !client.getAuthorizationGrantTypes()
                        .contains(AuthorizationGrantType.REFRESH_TOKEN)
                || !client.getAuthorizationGrantTypes()
                        .contains(AuthorizationGrantType.AUTHORIZATION_CODE)) {
            return null;
        }
        if (context.getPrincipal() == null
                || !StringUtils.hasText(context.getPrincipal().getName())) {
            return null;
        }
        byte[] value = new byte[TOKEN_BYTES];
        random.nextBytes(value);
        Instant issuedAt = Instant.now();
        return new OAuth2RefreshToken(
                Base64.getUrlEncoder().withoutPadding().encodeToString(value),
                issuedAt,
                issuedAt.plus(properties.refreshTokenTtl()));
    }
}
