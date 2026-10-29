package local.ptmcp.authz.oauth;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenRevocationAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

import java.util.List;

/**
 * RFC 7009 revocation with ownership enforcement. Unknown tokens and tokens belonging to another
 * client return the same successful authentication without touching storage, so the endpoint cannot
 * be used to enumerate tokens. Refresh lookup here is side-effect free; grant lookup intentionally
 * detects replay and must never run before revocation ownership is checked. Access JWTs are locally
 * verified before their persisted family binding is used.
 */
final class PtmcpTokenRevocationAuthenticationProvider implements AuthenticationProvider {
    private final PtmcpOAuth2AuthorizationService authorizations;
    private final JwtDecoder decoder;
    private final OAuthProperties properties;

    PtmcpTokenRevocationAuthenticationProvider(
            PtmcpOAuth2AuthorizationService authorizations,
            JwtDecoder decoder,
            OAuthProperties properties) {
        this.authorizations = authorizations;
        this.decoder = decoder;
        this.properties = properties;
    }

    @Override
    public Authentication authenticate(Authentication authentication)
            throws AuthenticationException {
        OAuth2TokenRevocationAuthenticationToken request =
                (OAuth2TokenRevocationAuthenticationToken) authentication;
        Object principal = request.getPrincipal();
        if (!(principal instanceof OAuth2ClientAuthenticationToken clientToken)
                || !clientToken.isAuthenticated()
                || clientToken.getRegisteredClient() == null) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT);
        }
        RegisteredClient client = clientToken.getRegisteredClient();
        if (!authorizations.revokeRefreshForClient(request.getToken(), client.getId())) {
            revokeAccess(request.getToken(), client);
        }
        return new OAuth2TokenRevocationAuthenticationToken(request.getToken(), clientToken, null);
    }

    private void revokeAccess(String token, RegisteredClient client) {
        Jwt jwt;
        try {
            jwt = decoder.decode(token);
        } catch (JwtException | IllegalArgumentException invalid) {
            return;
        }
        if (!"RS256".equals(jwt.getHeaders().get("alg"))
                || !properties.issuer().equals(jwt.getClaims().get("iss"))
                || !List.of(properties.audience()).equals(jwt.getAudience())
                || !client.getClientId().equals(jwt.getClaims().get("client_id"))
                || !(jwt.getClaims().get("sid") instanceof String sid)
                || !(jwt.getClaims().get("sub") instanceof String subject)
                || !(jwt.getClaims().get("auth_version") instanceof Number authVersion)
                || !(jwt.getClaims().get("platform_id") instanceof Number platformId)) {
            return;
        }
        long userId;
        try {
            userId = Long.parseLong(subject);
            if (userId <= 0 || !Long.toString(userId).equals(subject)) {
                return;
            }
        } catch (NumberFormatException invalid) {
            return;
        }
        authorizations.revokeAccessForClient(
                sid, client.getId(), userId, platformId.longValue(), authVersion.longValue());
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return OAuth2TokenRevocationAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
