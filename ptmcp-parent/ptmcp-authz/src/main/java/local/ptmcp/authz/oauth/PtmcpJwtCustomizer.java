package local.ptmcp.authz.oauth;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

import java.util.List;

/**
 * Replaces the default client-id audience with the single deployed MCP resource and pins the token
 * to the persisted user/family/auth version. Missing state is a server error, never a guess.
 */
public final class PtmcpJwtCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {
    private final OAuthProperties properties;

    public PtmcpJwtCustomizer(OAuthProperties properties) {
        this.properties = properties;
    }

    @Override
    public void customize(JwtEncodingContext context) {
        if (!OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
            return;
        }
        OAuth2Authorization authorization = context.getAuthorization();
        OAuth2AuthorizationRequest request =
                authorization == null
                        ? null
                        : authorization.getAttribute(OAuth2AuthorizationRequest.class.getName());
        Object grantedResource =
                request == null
                        ? null
                        : request.getAdditionalParameters()
                                .get(AuthorizationRequestPolicy.RESOURCE_PARAMETER);
        if (!properties.audience().equals(grantedResource)) {
            throw new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_GRANT);
        }
        AsPrincipal account = account(context.getPrincipal());
        String familyId = stringAttribute(authorization, OAuthAttributes.FAMILY_ID);
        long authVersion = longAttribute(authorization, OAuthAttributes.AUTH_VERSION);
        Object platformId =
                context.getRegisteredClient()
                        .getClientSettings()
                        .getSetting(PtmcpRegisteredClientRepository.PLATFORM_ID_SETTING);
        if (!(platformId instanceof Number number)) {
            throw serverError("Client platform binding is missing");
        }
        context.getClaims()
                .subject(Long.toString(account.userId()))
                .audience(List.of(properties.audience()))
                .claim("client_id", context.getRegisteredClient().getClientId())
                .claim("platform_id", number.longValue())
                .claim("sid", familyId)
                .claim("auth_version", authVersion);
    }

    private static AsPrincipal account(Authentication principal) {
        if (principal != null && principal.getPrincipal() instanceof AsPrincipal account) {
            return account;
        }
        throw serverError("Authorization principal is not an AS account");
    }

    private static String stringAttribute(OAuth2Authorization authorization, String name) {
        Object value = authorization == null ? null : authorization.getAttribute(name);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw serverError("Authorization family is missing");
    }

    private static long longAttribute(OAuth2Authorization authorization, String name) {
        Object value = authorization == null ? null : authorization.getAttribute(name);
        if (value instanceof Number number && number.longValue() > 0) {
            return number.longValue();
        }
        throw serverError("Authorization version is missing");
    }

    private static OAuth2AuthenticationException serverError(String description) {
        return new OAuth2AuthenticationException(
                new OAuth2Error(OAuth2ErrorCodes.SERVER_ERROR, description, null));
    }
}
