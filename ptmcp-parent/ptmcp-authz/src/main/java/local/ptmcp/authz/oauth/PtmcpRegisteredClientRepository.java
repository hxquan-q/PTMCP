package local.ptmcp.authz.oauth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads V3 client rows with hashed-only secrets. Token settings are fixed by AS policy rather than
 * trusted from per-row JSON; only the consent flag is read from client_settings.
 */
public final class PtmcpRegisteredClientRepository implements RegisteredClientRepository {
    static final String PLATFORM_ID_SETTING = "ptmcp.platform-id";
    private static final String SELECT_CLIENT =
            "SELECT c.id, c.client_id, c.client_id_issued_at, c.client_secret, "
                    + "c.client_secret_expires_at, c.client_name, c.client_authentication_methods, "
                    + "c.authorization_grant_types, c.redirect_uris, c.post_logout_redirect_uris, "
                    + "c.scopes, c.client_settings, c.platform_id, c.client_type, "
                    + "p.status AS platform_status "
                    + "FROM oauth2_registered_client c "
                    + "LEFT JOIN platform_registry p ON p.id = c.platform_id "
                    + "WHERE c.client_id = ? AND c.status = 'ACTIVE'";
    private static final String SELECT_CLIENT_BY_ID =
            "SELECT c.id, c.client_id, c.client_id_issued_at, c.client_secret, "
                    + "c.client_secret_expires_at, c.client_name, c.client_authentication_methods, "
                    + "c.authorization_grant_types, c.redirect_uris, c.post_logout_redirect_uris, "
                    + "c.scopes, c.client_settings, c.platform_id, c.client_type, "
                    + "p.status AS platform_status "
                    + "FROM oauth2_registered_client c "
                    + "LEFT JOIN platform_registry p ON p.id = c.platform_id "
                    + "WHERE c.id = ? AND c.status = 'ACTIVE'";

    private final JdbcTemplate jdbc;
    private final OAuthProperties properties;

    public PtmcpRegisteredClientRepository(JdbcTemplate jdbc, OAuthProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Override
    public void save(RegisteredClient registeredClient) {
        throw new UnsupportedOperationException(
                "Client registration is owned by the admin/DCR service, not the AS baseline");
    }

    @Override
    public RegisteredClient findById(String id) {
        return findOne(SELECT_CLIENT_BY_ID, id);
    }

    @Override
    public RegisteredClient findByClientId(String clientId) {
        return findOne(SELECT_CLIENT, clientId);
    }

    private RegisteredClient findOne(String sql, String key) {
        List<RegisteredClient> clients = jdbc.query(sql, (rs, row) -> map(rs), key);
        return clients.isEmpty() ? null : clients.getFirst();
    }

    private RegisteredClient map(ResultSet rs) throws SQLException {
        Long platformId = rs.getObject("platform_id", Long.class);
        String platformStatus = rs.getString("platform_status");
        String clientType = rs.getString("client_type");
        if (platformId == null || !"ACTIVE".equals(platformStatus)) {
            return null;
        }

        Set<ClientAuthenticationMethod> methods = new LinkedHashSet<>();
        OAuthStoreSupport.splitCsv(rs.getString("client_authentication_methods"))
                .forEach(method -> methods.add(new ClientAuthenticationMethod(method)));
        Set<AuthorizationGrantType> grants = new LinkedHashSet<>();
        OAuthStoreSupport.splitCsv(rs.getString("authorization_grant_types"))
                .forEach(grant -> grants.add(new AuthorizationGrantType(grant)));
        Set<String> redirectUris =
                new LinkedHashSet<>(OAuthStoreSupport.splitCsv(rs.getString("redirect_uris")));
        Set<String> postLogoutUris =
                new LinkedHashSet<>(
                        OAuthStoreSupport.splitCsv(rs.getString("post_logout_redirect_uris")));
        Set<String> scopes =
                new LinkedHashSet<>(OAuthStoreSupport.splitCsv(rs.getString("scopes")));

        // This slice is code-only: refresh/password/implicit/client_credentials rows are ignored
        // so neither the AS nor a hand-edited client row can mint a token outside the code flow.
        grants.retainAll(Set.of(AuthorizationGrantType.AUTHORIZATION_CODE));

        if ("PUBLIC".equals(clientType)) {
            if (!methods.isEmpty() && !methods.equals(Set.of(ClientAuthenticationMethod.NONE))) {
                return null;
            }
            methods.clear();
            methods.add(ClientAuthenticationMethod.NONE);
        } else if (!"CONFIDENTIAL".equals(clientType)
                || rs.getString("client_secret") == null
                || rs.getString("client_secret").isBlank()) {
            return null;
        }
        if (methods.isEmpty() || redirectUris.isEmpty() || scopes.isEmpty()) {
            return null;
        }

        ClientSettings.Builder clientSettings =
                ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(
                                requiresConsent(rs.getString("client_settings")))
                        .setting(PLATFORM_ID_SETTING, platformId);
        TokenSettings tokenSettings =
                TokenSettings.builder()
                        .accessTokenFormat(OAuth2TokenFormat.SELF_CONTAINED)
                        .authorizationCodeTimeToLive(properties.authorizationCodeTtl())
                        .accessTokenTimeToLive(properties.accessTokenTtl())
                        .reuseRefreshTokens(false)
                        .build();

        RegisteredClient.Builder builder =
                RegisteredClient.withId(rs.getString("id"))
                        .clientId(rs.getString("client_id"))
                        .clientIdIssuedAt(
                                toInstant(
                                        rs.getObject(
                                                "client_id_issued_at",
                                                java.time.LocalDateTime.class)))
                        .clientName(rs.getString("client_name"))
                        .clientAuthenticationMethods(value -> methods.forEach(value::add))
                        .authorizationGrantTypes(value -> grants.forEach(value::add))
                        .redirectUris(value -> redirectUris.forEach(value::add))
                        .postLogoutRedirectUris(value -> postLogoutUris.forEach(value::add))
                        .scopes(value -> scopes.forEach(value::add))
                        .clientSettings(clientSettings.build())
                        .tokenSettings(tokenSettings);
        String secret = rs.getString("client_secret");
        if (secret != null) {
            builder.clientSecret(secret);
            java.time.LocalDateTime secretExpiry =
                    rs.getObject("client_secret_expires_at", java.time.LocalDateTime.class);
            if (secretExpiry != null) {
                builder.clientSecretExpiresAt(toInstant(secretExpiry));
            }
        }
        return builder.build();
    }

    private static boolean requiresConsent(String clientSettingsJson) {
        if (clientSettingsJson == null || clientSettingsJson.isBlank()) {
            return false;
        }
        Map<String, Object> settings =
                OAuthStoreSupport.JSON.readValue(clientSettingsJson, Map.class);
        return Boolean.parseBoolean(
                String.valueOf(settings.get("settings.client.require-authorization-consent")));
    }

    private static Instant toInstant(java.time.LocalDateTime value) {
        return OAuthStoreSupport.toInstant(value);
    }
}
