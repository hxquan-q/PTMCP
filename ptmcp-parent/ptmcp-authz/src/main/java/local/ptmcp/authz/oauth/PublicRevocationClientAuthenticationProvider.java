package local.ptmcp.authz.oauth;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

/** Authenticates a registered public client for RFC 7009 revocation without a code_verifier. */
final class PublicRevocationClientAuthenticationProvider implements AuthenticationProvider {
    private final RegisteredClientRepository clients;

    PublicRevocationClientAuthenticationProvider(RegisteredClientRepository clients) {
        this.clients = clients;
    }

    @Override
    public Authentication authenticate(Authentication authentication)
            throws AuthenticationException {
        OAuth2ClientAuthenticationToken token = (OAuth2ClientAuthenticationToken) authentication;
        if (!ClientAuthenticationMethod.NONE.equals(token.getClientAuthenticationMethod())
                || !Boolean.TRUE.equals(
                        token.getAdditionalParameters()
                                .get(
                                        PublicRevocationClientAuthenticationConverter
                                                .REVOCATION_MARKER))) {
            return null;
        }
        RegisteredClient client = clients.findByClientId(token.getPrincipal().toString());
        if (client == null
                || !client.getClientAuthenticationMethods()
                        .contains(ClientAuthenticationMethod.NONE)) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_CLIENT);
        }
        return new OAuth2ClientAuthenticationToken(client, ClientAuthenticationMethod.NONE, null);
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return OAuth2ClientAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
