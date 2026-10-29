package local.ptmcp.authz.oauth;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.web.authentication.OAuth2AuthorizationCodeAuthenticationConverter;
import org.springframework.security.oauth2.server.authorization.web.authentication.OAuth2RefreshTokenAuthenticationConverter;
import org.springframework.security.web.authentication.AuthenticationConverter;

/** Validate the resource before grant processing can consume a code or refresh token. */
final class TokenRequestPolicy implements AuthenticationConverter {
    private final AuthenticationConverter code =
            new OAuth2AuthorizationCodeAuthenticationConverter();
    private final AuthenticationConverter refresh = new OAuth2RefreshTokenAuthenticationConverter();
    private final String resource;

    TokenRequestPolicy(String resource) {
        this.resource = resource;
    }

    @Override
    public Authentication convert(HttpServletRequest request) {
        String[] grants = request.getParameterValues(OAuth2ParameterNames.GRANT_TYPE);
        if (grants == null || grants.length != 1) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_REQUEST);
        }
        boolean isCode = AuthorizationGrantType.AUTHORIZATION_CODE.getValue().equals(grants[0]);
        boolean isRefresh = AuthorizationGrantType.REFRESH_TOKEN.getValue().equals(grants[0]);
        if (!isCode && !isRefresh) {
            return null;
        }
        if (!"POST".equals(request.getMethod())
                || request.getQueryString() != null
                || !isForm(request.getContentType())) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_REQUEST);
        }
        String[] resources =
                request.getParameterValues(AuthorizationRequestPolicy.RESOURCE_PARAMETER);
        if (resources == null || resources.length != 1 || !resource.equals(resources[0])) {
            throw new OAuth2AuthenticationException("invalid_target");
        }
        if (request.getParameterMap().values().stream().anyMatch(values -> values.length != 1)) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_REQUEST);
        }
        return (isCode ? code : refresh).convert(request);
    }

    private static boolean isForm(String contentType) {
        if (contentType == null) {
            return false;
        }
        try {
            return MediaType.APPLICATION_FORM_URLENCODED.isCompatibleWith(
                    MediaType.parseMediaType(contentType));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
