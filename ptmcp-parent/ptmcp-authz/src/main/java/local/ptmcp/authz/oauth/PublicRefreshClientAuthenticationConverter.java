package local.ptmcp.authz.oauth;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Public clients have no secret and the stock PKCE converter requires a code_verifier, which a
 * refresh request does not carry. This converter recognizes only that narrow case and leaves every
 * other token request to the default converters.
 */
final class PublicRefreshClientAuthenticationConverter implements AuthenticationConverter {
    @Override
    public Authentication convert(HttpServletRequest request) {
        if (!request.getRequestURI().endsWith("/oauth2/token")
                || !"POST".equalsIgnoreCase(request.getMethod())) {
            return null;
        }
        Map<String, String[]> parameters = request.getParameterMap();
        String grantType = single(parameters, OAuth2ParameterNames.GRANT_TYPE);
        String clientId = single(parameters, OAuth2ParameterNames.CLIENT_ID);
        if (!"refresh_token".equals(grantType)
                || !StringUtils.hasText(clientId)
                || parameters.containsKey(OAuth2ParameterNames.CLIENT_SECRET)) {
            return null;
        }
        if (!parameters.containsKey(OAuth2ParameterNames.REFRESH_TOKEN)
                || single(parameters, OAuth2ParameterNames.REFRESH_TOKEN) == null) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_REQUEST);
        }
        Map<String, Object> additional = new LinkedHashMap<>();
        additional.put(OAuth2ParameterNames.GRANT_TYPE, grantType);
        additional.put(
                OAuth2ParameterNames.REFRESH_TOKEN,
                single(parameters, OAuth2ParameterNames.REFRESH_TOKEN));
        return new OAuth2ClientAuthenticationToken(
                clientId, ClientAuthenticationMethod.NONE, null, additional);
    }

    private static String single(Map<String, String[]> parameters, String name) {
        String[] values = parameters.get(name);
        return values == null || values.length != 1 ? null : values[0];
    }
}
