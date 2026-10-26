package local.ptmcp.authz.oauth;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenRevocationAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

/**
 * RFC 7009 revocation with ownership enforcement. Unknown tokens and tokens belonging to another
 * client return the same successful authentication without touching storage, so the endpoint cannot
 * be used to enumerate tokens. A known refresh token is invalidated through the authorization
 * service, which revokes the whole family and leaves tombstones for later replay detection.
 */
final class PtmcpTokenRevocationAuthenticationProvider implements AuthenticationProvider {
    private final OAuth2AuthorizationService authorizations;

    PtmcpTokenRevocationAuthenticationProvider(OAuth2AuthorizationService authorizations) {
        this.authorizations = authorizations;
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
        OAuth2Authorization authorization = authorizations.findByToken(request.getToken(), null);
        if (authorization == null
                || !client.getId().equals(authorization.getRegisteredClientId())) {
            return new OAuth2TokenRevocationAuthenticationToken(
                    request.getToken(), clientToken, null);
        }
        OAuth2Authorization.Token<OAuth2Token> token = authorization.getToken(request.getToken());
        if (token == null) {
            return new OAuth2TokenRevocationAuthenticationToken(
                    request.getToken(), clientToken, null);
        }
        OAuth2Authorization invalidated =
                OAuth2Authorization.from(authorization).invalidate(token.getToken()).build();
        authorizations.save(invalidated);
        return new OAuth2TokenRevocationAuthenticationToken(token.getToken(), clientToken);
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return OAuth2TokenRevocationAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
