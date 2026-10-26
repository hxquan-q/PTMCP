package local.ptmcp.authz.oauth;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2ClientAuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/** Public client authentication for the RFC 7009 revocation endpoint (no PKCE verifier). */
final class PublicRevocationClientAuthenticationConverter implements AuthenticationConverter {
    static final String REVOCATION_MARKER = "ptmcp.revocation";

    @Override
    public Authentication convert(HttpServletRequest request) {
        if (!request.getRequestURI().endsWith("/oauth2/revoke")
                || !"POST".equalsIgnoreCase(request.getMethod())) {
            return null;
        }
        Map<String, String[]> parameters = request.getParameterMap();
        String clientId = single(parameters, OAuth2ParameterNames.CLIENT_ID);
        String token = single(parameters, OAuth2ParameterNames.TOKEN);
        if (!StringUtils.hasText(clientId)
                || !StringUtils.hasText(token)
                || parameters.containsKey(OAuth2ParameterNames.CLIENT_SECRET)) {
            return null;
        }
        Map<String, Object> additional = new LinkedHashMap<>();
        additional.put(REVOCATION_MARKER, Boolean.TRUE);
        return new OAuth2ClientAuthenticationToken(
                clientId, ClientAuthenticationMethod.NONE, null, additional);
    }

    private static String single(Map<String, String[]> parameters, String name) {
        String[] values = parameters.get(name);
        return values == null || values.length != 1 ? null : values[0];
    }
}
