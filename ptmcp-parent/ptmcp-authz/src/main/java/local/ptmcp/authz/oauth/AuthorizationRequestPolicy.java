package local.ptmcp.authz.oauth;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.web.authentication.OAuth2AuthorizationCodeRequestAuthenticationConverter;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.util.StringUtils;

/**
 * Adds the deployment rules SAS leaves optional: an authenticated AS session, a CSRF state and a
 * resource restricted to the single configured MCP audience. Errors stay in OAuth form and are
 * never redirected before the redirect URI has been validated.
 */
public final class AuthorizationRequestPolicy implements AuthenticationConverter {
    private static final String ERROR_URI =
            "https://datatracker.ietf.org/doc/html/rfc6749#section-4.1.2.1";
    static final String RESOURCE_PARAMETER = "resource";

    private final OAuth2AuthorizationCodeRequestAuthenticationConverter delegate =
            new OAuth2AuthorizationCodeRequestAuthenticationConverter();
    private final RegisteredClientRepository clients;
    private final String audience;

    public AuthorizationRequestPolicy(RegisteredClientRepository clients, String audience) {
        this.clients = clients;
        this.audience = audience;
    }

    @Override
    public Authentication convert(HttpServletRequest request) {
        Authentication converted = delegate.convert(request);
        if (!(converted instanceof OAuth2AuthorizationCodeRequestAuthenticationToken token)) {
            return converted;
        }
        if (!StringUtils.hasText(token.getState())) {
            throw invalidParameter(OAuth2ParameterNames.STATE);
        }
        if (!StringUtils.hasText(token.getRedirectUri())) {
            throw invalidParameter(OAuth2ParameterNames.REDIRECT_URI);
        }
        Object resource = token.getAdditionalParameters().get(RESOURCE_PARAMETER);
        if (!(resource instanceof String value) || !audience.equals(value)) {
            throw invalidParameter(RESOURCE_PARAMETER);
        }
        // Exact match before SAS can build an error redirect: an unregistered URI is never a
        // redirect target, not even for an OAuth error response.
        RegisteredClient client = clients.findByClientId(token.getClientId());
        if (client == null || !client.getRedirectUris().contains(token.getRedirectUri())) {
            throw invalidParameter(OAuth2ParameterNames.REDIRECT_URI);
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean anonymous =
                authentication == null
                        || !authentication.isAuthenticated()
                        || authentication instanceof AnonymousAuthenticationToken;
        if (anonymous) {
            // Let the authorization rules send the request to the AS login page instead of
            // producing an OAuth error an unauthenticated user could never act on.
            return token;
        }
        if (!(authentication.getPrincipal() instanceof AsPrincipal)) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error(OAuth2ErrorCodes.ACCESS_DENIED));
        }
        return token;
    }

    private static OAuth2AuthorizationCodeRequestAuthenticationException invalidParameter(
            String parameter) {
        OAuth2Error error =
                new OAuth2Error(
                        OAuth2ErrorCodes.INVALID_REQUEST,
                        "OAuth 2.0 Parameter: " + parameter,
                        ERROR_URI);
        // No token is attached on purpose: the redirect URI has not been validated yet.
        return new OAuth2AuthorizationCodeRequestAuthenticationException(error, null);
    }
}
