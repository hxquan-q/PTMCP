package local.ptmcp.authz.oauth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Minimal JDBC consent store over oauth2_authorization_consent. */
public final class PtmcpOAuth2AuthorizationConsentService
        implements OAuth2AuthorizationConsentService {
    private static final String SELECT =
            "SELECT authorities, scopes FROM oauth2_authorization_consent "
                    + "WHERE registered_client_id = ? AND principal_name = ?";
    private static final String UPSERT =
            "INSERT INTO oauth2_authorization_consent (registered_client_id, principal_name, "
                    + "authorities, scopes, version, create_time, update_time) "
                    + "VALUES (?, ?, ?, ?, 1, ?, ?) AS incoming "
                    + "ON DUPLICATE KEY UPDATE authorities = incoming.authorities, "
                    + "scopes = incoming.scopes, version = oauth2_authorization_consent.version + 1, "
                    + "update_time = incoming.update_time";
    private static final String DELETE =
            "DELETE FROM oauth2_authorization_consent "
                    + "WHERE registered_client_id = ? AND principal_name = ?";

    private final JdbcTemplate jdbc;

    public PtmcpOAuth2AuthorizationConsentService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(OAuth2AuthorizationConsent authorizationConsent) {
        Instant now = Instant.now();
        LocalDateTime timestamp = OAuthStoreSupport.toUtc(now);
        jdbc.update(
                UPSERT,
                authorizationConsent.getRegisteredClientId(),
                authorizationConsent.getPrincipalName(),
                String.join(
                        ",",
                        authorizationConsent.getAuthorities().stream()
                                .map(GrantedAuthority::getAuthority)
                                .toList()),
                OAuthStoreSupport.toJson(
                        authorizationConsent.getScopes().stream().sorted().toList()),
                timestamp,
                timestamp);
    }

    @Override
    public void remove(OAuth2AuthorizationConsent authorizationConsent) {
        jdbc.update(
                DELETE,
                authorizationConsent.getRegisteredClientId(),
                authorizationConsent.getPrincipalName());
    }

    @Override
    public OAuth2AuthorizationConsent findById(String registeredClientId, String principalName) {
        if (!StringUtils.hasText(registeredClientId) || !StringUtils.hasText(principalName)) {
            return null;
        }
        List<OAuth2AuthorizationConsent> consents =
                jdbc.query(
                        SELECT,
                        (rs, row) -> {
                            Set<String> scopes = scopes(rs.getString("scopes"));
                            Set<GrantedAuthority> authorities =
                                    new LinkedHashSet<>(
                                            OAuthStoreSupport.splitCsv(rs.getString("authorities"))
                                                    .stream()
                                                    .map(SimpleGrantedAuthority::new)
                                                    .map(GrantedAuthority.class::cast)
                                                    .toList());
                            OAuth2AuthorizationConsent.Builder builder =
                                    OAuth2AuthorizationConsent.withId(
                                            registeredClientId, principalName);
                            scopes.forEach(builder::scope);
                            authorities.forEach(builder::authority);
                            return builder.build();
                        },
                        registeredClientId,
                        principalName);
        return consents.isEmpty() ? null : consents.getFirst();
    }

    private static Set<String> scopes(String json) {
        Set<String> scopes = new LinkedHashSet<>();
        if (json == null || json.isBlank()) {
            return scopes;
        }
        Object parsed = OAuthStoreSupport.JSON.readValue(json, Object.class);
        if (parsed instanceof List<?> values) {
            values.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .forEach(scopes::add);
        }
        return scopes;
    }
}
