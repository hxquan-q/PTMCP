package local.ptmcp.authz.oauth;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/**
 * Authenticates a public client only for the refresh_token grant. It intentionally does not replace
 * the stock PKCE provider for the authorization-code exchange: code exchange still requires the
 * code_verifier, while refresh proves possession of the stored refresh token and the client_id.
 */
final class PublicRefreshClientAuthenticationProvider implements AuthenticationProvider {
    private final RegisteredClientRepository clients;

    PublicRefreshClientAuthenticationProvider(RegisteredClientRepository clients) {
        this.clients = clients;
    }

    @Override
    public Authentication authenticate(Authentication authentication)
            throws AuthenticationException {
        OAuth2ClientAuthenticationToken token = (OAuth2ClientAuthenticationToken) authentication;
        if (!ClientAuthenticationMethod.NONE.equals(token.getClientAuthenticationMethod())) {
            return null;
        }
        Object grantType = token.getAdditionalParameters().get(OAuth2ParameterNames.GRANT_TYPE);
        if (!"refresh_token".equals(grantType)) {
            return null;
        }
        String clientId = token.getPrincipal().toString();
        RegisteredClient client = clients.findByClientId(clientId);
        if (client == null
                || !client.getClientAuthenticationMethods()
                        .contains(ClientAuthenticationMethod.NONE)
                || !client.getAuthorizationGrantTypes()
                        .contains(AuthorizationGrantType.REFRESH_TOKEN)
                || !client.getAuthorizationGrantTypes()
                        .contains(AuthorizationGrantType.AUTHORIZATION_CODE)) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT);
        }
        return new OAuth2ClientAuthenticationToken(client, ClientAuthenticationMethod.NONE, null);
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return OAuth2ClientAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
