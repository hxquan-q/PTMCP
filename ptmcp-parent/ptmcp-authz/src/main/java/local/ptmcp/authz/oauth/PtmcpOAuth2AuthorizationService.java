package local.ptmcp.authz.oauth;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.persistence.security.UserSecurityStore;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.Principal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Authorization store over V3 metadata tables. Raw codes, access tokens and refresh tokens never
 * reach the database; only the code digest and token lifetimes are persisted.
 */
public class PtmcpOAuth2AuthorizationService implements OAuth2AuthorizationService {
    static final OAuth2TokenType CODE_TOKEN_TYPE = new OAuth2TokenType(OAuth2ParameterNames.CODE);
    static final OAuth2TokenType STATE_TOKEN_TYPE = new OAuth2TokenType(OAuth2ParameterNames.STATE);

    private static final String COLUMNS =
            "id, registered_client_id, principal_name, authorized_scopes, attributes, state, "
                    + "authorization_code_hash, code_expires_at, code_consumed_at, redirect_uri, "
                    + "pkce_challenge, pkce_method, scopes, user_id, family_id, auth_version, status, "
                    + "expires_at, access_token_issued_at, access_token_expires_at, "
                    + "access_token_type, access_token_scopes, oidc_id_token_issued_at, "
                    + "oidc_id_token_expires_at, refresh_token_issued_at, refresh_token_expires_at, "
                    + "create_time, update_time";
    private static final String SELECT_BY_ID =
            "SELECT " + COLUMNS + " FROM oauth2_authorization WHERE id = ? AND status = 'ACTIVE'";
    private static final String SELECT_BY_CODE_HASH =
            "SELECT "
                    + COLUMNS
                    + " FROM oauth2_authorization "
                    + "WHERE authorization_code_hash = ? AND status = 'ACTIVE'";
    private static final String SELECT_BY_STATE =
            "SELECT "
                    + COLUMNS
                    + " FROM oauth2_authorization "
                    + "WHERE state = ? AND status = 'ACTIVE' ORDER BY create_time DESC LIMIT 1";
    private static final String INSERT =
            "INSERT INTO oauth2_authorization (id, registered_client_id, principal_name, "
                    + "authorization_grant_type, authorized_scopes, attributes, state, "
                    + "authorization_code_hash, code_expires_at, code_consumed_at, redirect_uri, "
                    + "pkce_challenge, pkce_method, scopes, user_id, family_id, auth_version, status, "
                    + "version, expires_at, access_token_issued_at, access_token_expires_at, "
                    + "access_token_type, access_token_scopes, oidc_id_token_issued_at, "
                    + "oidc_id_token_expires_at, refresh_token_issued_at, refresh_token_expires_at, "
                    + "create_time, update_time, created_by, updated_by) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?, "
                    + "?, ?, ?, ?, ?, ?, ?, ?)";
    private static final String CONSUME_CODE =
            "UPDATE oauth2_authorization SET code_consumed_at = ?, update_time = ?, "
                    + "updated_by = ?, version = version + 1 "
                    + "WHERE id = ? AND code_consumed_at IS NULL AND authorization_code_hash = ?";
    private static final String UPDATE =
            "UPDATE oauth2_authorization SET authorized_scopes = ?, attributes = ?, state = ?, "
                    + "authorization_code_hash = ?, code_expires_at = ?, redirect_uri = ?, "
                    + "pkce_challenge = ?, pkce_method = ?, scopes = ?, family_id = ?, "
                    + "auth_version = ?, status = ?, expires_at = ?, access_token_issued_at = ?, "
                    + "access_token_expires_at = ?, access_token_type = ?, access_token_scopes = ?, "
                    + "oidc_id_token_issued_at = ?, oidc_id_token_expires_at = ?, "
                    + "refresh_token_issued_at = ?, refresh_token_expires_at = ?, update_time = ?, "
                    + "updated_by = ?, version = version + 1 WHERE id = ?";
    private static final String DELETE = "DELETE FROM oauth2_authorization WHERE id = ?";
    private static final String SELECT_AUTHORIZATION_FAMILY =
            "SELECT family_id FROM oauth2_authorization WHERE id = ?";
    private static final String SELECT_REFRESH =
            "SELECT t.family_id, t.generation, t.issued_at, t.expires_at, t.consumed_at,"
                    + " t.status AS token_status, f.authorization_id, f.status AS family_status,"
                    + " f.absolute_expires_at, f.idle_expires_at, f.auth_version AS family_auth_version"
                    + " FROM oauth_refresh_token t JOIN oauth_refresh_family f ON f.id = t.family_id"
                    + " WHERE t.token_hash = ?";
    private static final String SELECT_FAMILY =
            "SELECT status, absolute_expires_at, idle_expires_at FROM oauth_refresh_family"
                    + " WHERE id = ?";
    private static final String SELECT_REFRESH_BY_HASH =
            "SELECT token_hash, generation FROM oauth_refresh_token WHERE token_hash = ?"
                    + " AND family_id = ?";
    private static final String INSERT_FAMILY =
            "INSERT INTO oauth_refresh_family (id, authorization_id, user_id,"
                    + " registered_client_id, platform_id, status, auth_version,"
                    + " absolute_expires_at, idle_expires_at, last_rotated_at, create_time,"
                    + " update_time, version) VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?, 1)";
    private static final String INSERT_REFRESH =
            "INSERT INTO oauth_refresh_token (token_hash, family_id, generation, issued_at,"
                    + " expires_at, status, create_time, update_time) VALUES (?, ?, ?, ?, ?,"
                    + " 'ACTIVE', ?, ?)";
    private static final String CONSUME_REFRESH =
            "UPDATE oauth_refresh_token SET status = 'CONSUMED', consumed_at = ?,"
                    + " successor_hash = ?, update_time = ? WHERE token_hash = ?"
                    + " AND status = 'ACTIVE'";
    private static final String UPDATE_FAMILY =
            "UPDATE oauth_refresh_family SET idle_expires_at = ?, last_rotated_at = ?,"
                    + " version = version + 1, update_time = ? WHERE id = ? AND status = 'ACTIVE'";
    private static final String REVOKE_FAMILY =
            "UPDATE oauth_refresh_family SET status = 'REVOKED', revoked_at = ?,"
                    + " revoke_reason = ?, version = version + 1, update_time = ?"
                    + " WHERE id = ? AND status <> 'REVOKED'";
    private static final String REVOKE_FAMILY_TOKENS =
            "UPDATE oauth_refresh_token SET status = 'REVOKED', update_time = ?"
                    + " WHERE family_id = ? AND status <> 'REVOKED'";
    private static final String REVOKE_FAMILY_AUTHORIZATION =
            "UPDATE oauth2_authorization SET status = 'REVOKED', version = version + 1,"
                    + " update_time = ? WHERE family_id = ? AND status <> 'REVOKED'";

    private final JdbcTemplate jdbc;
    private final PtmcpRegisteredClientRepository registeredClients;
    private final OAuthProperties properties;

    public PtmcpOAuth2AuthorizationService(
            JdbcTemplate jdbc,
            PtmcpRegisteredClientRepository registeredClients,
            OAuthProperties properties) {
        this.jdbc = jdbc;
        this.registeredClients = registeredClients;
        this.properties = properties;
    }

    @Override
    @Transactional(noRollbackFor = RefreshReplayException.class)
    public void save(OAuth2Authorization authorization) {
        lockSecurityEpoch();
        StoredAuthorization existing =
                jdbc.query(SELECT_BY_ID, (rs, row) -> map(rs), authorization.getId()).stream()
                        .findFirst()
                        .orElse(null);
        if (existing == null
                && !jdbc.queryForList(
                                "SELECT id FROM oauth2_authorization WHERE id = ?",
                                String.class,
                                authorization.getId())
                        .isEmpty()) {
            throw invalidGrant();
        }
        byte[] presentedRefresh = attributeBytes(authorization, OAuthAttributes.REFRESH_TOKEN_HASH);
        if (presentedRefresh != null) {
            if (existing == null || existing.familyId() == null) {
                throw invalidGrant();
            }
            List<String> status =
                    jdbc.queryForList(
                            "SELECT status FROM oauth_refresh_token WHERE token_hash = ? AND family_id = ?",
                            String.class,
                            presentedRefresh,
                            existing.familyId());
            if (status.isEmpty()) {
                throw invalidGrant();
            }
            if (!"ACTIVE".equals(status.getFirst())) {
                // Commit only the replay revocation; this check precedes every grant mutation.
                revokeFamily(existing.familyId(), "REFRESH_REPLAY");
                throw new RefreshReplayException();
            }
        }
        OAuth2AuthorizationRequest request =
                authorization.getAttribute(OAuth2AuthorizationRequest.class.getName());
        Authentication principal = authorization.getAttribute(Principal.class.getName());
        AsPrincipal account = requireAccount(principal, authorization.getPrincipalName());
        OAuth2Authorization.Token<OAuth2AuthorizationCode> code =
                authorization.getToken(OAuth2AuthorizationCode.class);

        byte[] codeHash =
                code == null ? null : OAuthStoreSupport.sha256(code.getToken().getTokenValue());
        boolean exchange = code != null && code.isInvalidated();
        Instant now = Instant.now();
        LocalDateTime timestamp = OAuthStoreSupport.toUtc(now);

        String familyId = existing != null ? existing.familyId() : null;
        if (familyId == null && code != null) {
            familyId = UUID.randomUUID().toString();
        }
        long authVersion = existing != null ? existing.authVersion() : account.authVersion();
        try {
            UserSecurityStore.State subject =
                    jdbc.execute(
                            (ConnectionCallback<UserSecurityStore.State>)
                                    connection -> {
                                        UserSecurityStore.State value =
                                                UserSecurityStore.read(
                                                        connection, account.userId(), true);
                                        UserSecurityStore.requireActiveProfile(
                                                connection, account.userId());
                                        return value;
                                    });
            UserSecurityStore.requireReady(subject, authVersion);
            if (account.authVersion() != authVersion) {
                throw invalidGrant();
            }
        } catch (DomainException unavailable) {
            throw invalidGrant();
        }

        Set<String> scopes = new LinkedHashSet<>(authorization.getAuthorizedScopes());
        if (scopes.isEmpty() && request != null && request.getScopes() != null) {
            // Consent is persisted before a code exists and has no authorized scopes yet; the
            // requested scopes must survive so the state lookup can reconstruct the request.
            scopes.addAll(request.getScopes());
        }
        String scopesCsv = String.join(",", scopes);
        String scopesJson = OAuthStoreSupport.toJson(new ArrayList<>(scopes));
        String state = authorization.getAttribute(OAuth2ParameterNames.STATE);
        String redirectUri = request == null ? null : request.getRedirectUri();
        String challenge =
                request == null
                        ? null
                        : stringParameter(
                                request.getAdditionalParameters(),
                                PkceParameterNames.CODE_CHALLENGE);
        String challengeMethod =
                request == null
                        ? null
                        : stringParameter(
                                request.getAdditionalParameters(),
                                PkceParameterNames.CODE_CHALLENGE_METHOD);
        if (challenge == null && existing != null && existing.codeHash() != null) {
            challenge = existing.pkceChallenge();
            challengeMethod = existing.pkceMethod();
            redirectUri = existing.redirectUri();
        }

        OAuth2Authorization.Token<OAuth2AccessToken> accessToken = authorization.getAccessToken();
        OAuth2Authorization.Token<OidcIdToken> idToken = authorization.getToken(OidcIdToken.class);
        OAuth2Authorization.Token<OAuth2RefreshToken> refreshToken =
                authorization.getRefreshToken();
        if (refreshToken != null && refreshToken.isInvalidated()) {
            if (familyId != null) {
                revokeFamily(familyId, "CLIENT_REVOKED");
            }
            return;
        }
        FamilyState family = familyId == null ? null : familyState(familyId);
        if (family != null && !"ACTIVE".equals(family.status())) {
            throw invalidGrant();
        }
        Instant familyAbsolute =
                family == null
                        ? now.plus(properties.refreshFamilyAbsoluteTtl())
                        : family.absoluteExpiresAt();
        Instant refreshExpires =
                refreshToken == null
                        ? null
                        : earliest(refreshToken.getToken().getExpiresAt(), familyAbsolute);
        Instant expiresAt =
                latest(
                        code == null ? null : code.getToken().getExpiresAt(),
                        accessToken == null ? null : accessToken.getToken().getExpiresAt(),
                        idToken == null ? null : idToken.getToken().getExpiresAt(),
                        refreshExpires);
        Map<String, Object> attributeMap = attributes(account, familyId, authVersion);
        String resource =
                request == null
                        ? null
                        : stringParameter(
                                request.getAdditionalParameters(),
                                AuthorizationRequestPolicy.RESOURCE_PARAMETER);
        if (!properties.audience().equals(resource)
                || (existing != null
                        && !resource.equals(storedString(existing.attributes(), "resource")))) {
            // A deployment configuration change cannot rebind an existing grant to a new target.
            throw invalidGrant();
        }
        attributeMap.put("resource", resource);
        if (request != null && request.getState() != null) {
            attributeMap.put(OAuthAttributes.CLIENT_STATE, request.getState());
        }
        byte[] attributes =
                OAuthStoreSupport.toJson(attributeMap)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);

        if (exchange) {
            if (existing == null || existing.codeHash() == null || existing.consumedAt() != null) {
                throw invalidGrant();
            }
            int consumed =
                    jdbc.update(
                            CONSUME_CODE,
                            timestamp,
                            timestamp,
                            account.userId(),
                            authorization.getId(),
                            codeHash);
            if (consumed != 1) {
                throw invalidGrant();
            }
        }

        if (existing == null) {
            jdbc.update(
                    INSERT,
                    authorization.getId(),
                    authorization.getRegisteredClientId(),
                    authorization.getPrincipalName(),
                    AuthorizationGrantType.AUTHORIZATION_CODE.getValue(),
                    scopesCsv,
                    attributes,
                    state,
                    codeHash,
                    toTimestamp(code == null ? null : code.getToken().getExpiresAt()),
                    null,
                    redirectUri,
                    challenge,
                    challengeMethod,
                    scopesJson,
                    account.userId(),
                    familyId,
                    authVersion,
                    "ACTIVE",
                    toTimestamp(expiresAt),
                    toTimestamp(tokenIssuedAt(accessToken)),
                    toTimestamp(tokenExpiresAt(accessToken)),
                    accessToken == null ? null : "Bearer",
                    accessToken == null ? null : scopesCsv,
                    toTimestamp(tokenIssuedAt(idToken)),
                    toTimestamp(tokenExpiresAt(idToken)),
                    toTimestamp(tokenIssuedAt(refreshToken)),
                    toTimestamp(refreshExpires),
                    timestamp,
                    timestamp,
                    account.userId(),
                    account.userId());
        } else {
            jdbc.update(
                    UPDATE,
                    scopesCsv,
                    attributes,
                    state,
                    codeHash,
                    toTimestamp(code == null ? null : code.getToken().getExpiresAt()),
                    redirectUri,
                    challenge,
                    challengeMethod,
                    scopesJson,
                    familyId,
                    authVersion,
                    "ACTIVE",
                    toTimestamp(expiresAt),
                    toTimestamp(tokenIssuedAt(accessToken)),
                    toTimestamp(tokenExpiresAt(accessToken)),
                    accessToken == null ? null : "Bearer",
                    accessToken == null ? null : scopesCsv,
                    toTimestamp(tokenIssuedAt(idToken)),
                    toTimestamp(tokenExpiresAt(idToken)),
                    toTimestamp(tokenIssuedAt(refreshToken)),
                    toTimestamp(refreshExpires),
                    timestamp,
                    account.userId(),
                    authorization.getId());
        }
        if (familyId != null) {
            persistRefreshFamily(
                    familyId,
                    authorization,
                    account,
                    authVersion,
                    refreshToken,
                    refreshExpires,
                    now,
                    timestamp);
        }
    }

    private void persistRefreshFamily(
            String familyId,
            OAuth2Authorization authorization,
            AsPrincipal account,
            long authVersion,
            OAuth2Authorization.Token<OAuth2RefreshToken> refreshToken,
            Instant refreshExpires,
            Instant now,
            LocalDateTime timestamp) {
        FamilyState family = familyState(familyId);
        if (family == null) {
            RegisteredClient client =
                    registeredClients.findById(authorization.getRegisteredClientId());
            if (client == null) {
                throw invalidGrant();
            }
            Object platform =
                    client.getClientSettings()
                            .getSetting(PtmcpRegisteredClientRepository.PLATFORM_ID_SETTING);
            Long platformId = platform instanceof Number number ? number.longValue() : null;
            Instant absolute = now.plus(properties.refreshFamilyAbsoluteTtl());
            Instant idle = earliest(now.plus(properties.refreshTokenTtl()), absolute);
            jdbc.update(
                    INSERT_FAMILY,
                    familyId,
                    authorization.getId(),
                    account.userId(),
                    authorization.getRegisteredClientId(),
                    platformId,
                    authVersion,
                    toTimestamp(absolute),
                    toTimestamp(idle),
                    timestamp,
                    timestamp,
                    timestamp);
            family = new FamilyState("ACTIVE", absolute, idle);
        }
        if (refreshToken == null) {
            return;
        }
        byte[] nextHash = OAuthStoreSupport.sha256(refreshToken.getToken().getTokenValue());
        byte[] presentedHash = attributeBytes(authorization, OAuthAttributes.REFRESH_TOKEN_HASH);
        ActiveRefresh previous =
                presentedHash == null ? null : refreshByHash(familyId, presentedHash);
        long generation = previous == null ? 1 : previous.generation() + 1;
        if (previous != null) {
            int consumed =
                    jdbc.update(
                            CONSUME_REFRESH, timestamp, nextHash, timestamp, previous.tokenHash());
            if (consumed != 1) {
                revokeFamily(familyId, "REFRESH_REPLAY");
                throw invalidGrant();
            }
        }
        try {
            jdbc.update(
                    INSERT_REFRESH,
                    nextHash,
                    familyId,
                    generation,
                    timestamp,
                    toTimestamp(refreshExpires),
                    timestamp,
                    timestamp);
        } catch (DuplicateKeyException duplicate) {
            revokeFamily(familyId, "REFRESH_REPLAY");
            throw invalidGrant();
        }
        Instant idle = earliest(now.plus(properties.refreshTokenTtl()), family.absoluteExpiresAt());
        jdbc.update(UPDATE_FAMILY, toTimestamp(idle), timestamp, timestamp, familyId);
    }

    private FamilyState familyState(String familyId) {
        List<FamilyState> rows =
                jdbc.query(
                        SELECT_FAMILY,
                        (rs, index) ->
                                new FamilyState(
                                        rs.getString("status"),
                                        OAuthStoreSupport.toInstant(
                                                rs.getObject(
                                                        "absolute_expires_at",
                                                        LocalDateTime.class)),
                                        OAuthStoreSupport.toInstant(
                                                rs.getObject(
                                                        "idle_expires_at", LocalDateTime.class))),
                        familyId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private ActiveRefresh refreshByHash(String familyId, byte[] tokenHash) {
        List<ActiveRefresh> rows =
                jdbc.query(
                        SELECT_REFRESH_BY_HASH,
                        (rs, index) ->
                                new ActiveRefresh(
                                        rs.getBytes("token_hash"), rs.getLong("generation")),
                        tokenHash,
                        familyId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static byte[] attributeBytes(OAuth2Authorization authorization, String name) {
        Object value = authorization.getAttribute(name);
        return value instanceof byte[] bytes ? bytes.clone() : null;
    }

    @Override
    public OAuth2Authorization findById(String id) {
        if (!StringUtils.hasText(id)) {
            return null;
        }
        return jdbc.query(SELECT_BY_ID, (rs, row) -> map(rs), id).stream()
                .findFirst()
                .map(row -> reconstruct(row, null))
                .orElse(null);
    }

    /** Revocation lookup deliberately includes tombstones and has no grant-replay side effects. */
    @Transactional
    public boolean revokeRefreshForClient(String token, String registeredClientId) {
        List<String> families =
                jdbc.queryForList(
                        "SELECT f.id FROM oauth_refresh_token t JOIN oauth_refresh_family f"
                                + " ON f.id = t.family_id WHERE t.token_hash = ?"
                                + " AND f.registered_client_id = ?",
                        String.class,
                        OAuthStoreSupport.sha256(token),
                        registeredClientId);
        if (families.isEmpty()) {
            return false;
        }
        revokeOwnedFamily(families.getFirst(), registeredClientId);
        return true;
    }

    /**
     * Called only after local signature, issuer, audience and client validation of an access JWT.
     */
    @Transactional
    public void revokeAccessForClient(
            String familyId,
            String registeredClientId,
            long userId,
            long platformId,
            long authVersion) {
        List<String> families =
                jdbc.queryForList(
                        "SELECT id FROM oauth_refresh_family WHERE id = ? AND registered_client_id = ?"
                                + " AND user_id = ? AND platform_id = ? AND auth_version = ?",
                        String.class,
                        familyId,
                        registeredClientId,
                        userId,
                        platformId,
                        authVersion);
        if (!families.isEmpty()) {
            revokeOwnedFamily(familyId, registeredClientId);
        }
    }

    private void revokeOwnedFamily(String familyId, String registeredClientId) {
        lockSecurityEpoch();
        if (!jdbc.queryForList(
                        "SELECT id FROM oauth_refresh_family WHERE id = ? AND registered_client_id = ?",
                        String.class,
                        familyId,
                        registeredClientId)
                .isEmpty()) {
            revokeFamily(familyId, "CLIENT_REVOKED");
        }
    }

    private long lockSecurityEpoch() {
        Long epoch =
                jdbc.queryForObject(
                        "SELECT policy_epoch FROM sys_security_state WHERE singleton_id = 1 FOR UPDATE",
                        Long.class);
        if (epoch == null || epoch <= 0 || epoch == Long.MAX_VALUE) {
            throw new IllegalStateException("OAuth security state is unavailable");
        }
        return epoch;
    }

    private void revokeFamily(String familyId, String reason) {
        long epoch = lockSecurityEpoch();
        List<Long> versions =
                jdbc.queryForList(
                        "SELECT version FROM oauth_refresh_family WHERE id = ?"
                                + " AND status = 'ACTIVE' FOR UPDATE",
                        Long.class,
                        familyId);
        if (versions.isEmpty()) {
            return;
        }
        LocalDateTime now = OAuthStoreSupport.toUtc(Instant.now());
        jdbc.update(REVOKE_FAMILY, now, reason, now, familyId);
        jdbc.update(REVOKE_FAMILY_AUTHORIZATION, now, familyId);
        jdbc.update(REVOKE_FAMILY_TOKENS, now, familyId);
        jdbc.update(
                "UPDATE sys_security_state SET policy_epoch = ?,"
                        + " update_time = UTC_TIMESTAMP(6) WHERE singleton_id = 1",
                epoch + 1);
        jdbc.update(
                "INSERT INTO sys_security_event (event_id, epoch, aggregate_type,"
                        + " aggregate_id, event_type, payload, occurred_at, create_time, update_time)"
                        + " VALUES (?, ?, 'REFRESH_FAMILY', ?, 'REFRESH_FAMILY_CHANGED', ?,"
                        + " UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
                UUID.randomUUID().toString(),
                epoch + 1,
                familyId,
                OAuthStoreSupport.toJson(
                        Map.of(
                                "schemaVersion",
                                1,
                                "aggregateId",
                                familyId,
                                "version",
                                Math.addExact(versions.getFirst(), 1))));
    }

    @Override
    @Transactional
    public OAuth2Authorization findByToken(String token, OAuth2TokenType tokenType) {
        if (!StringUtils.hasText(token)) {
            return null;
        }
        // Serialize grant lookup/replay and writes with all primary security mutations. No
        // authorization/token row is locked before this gate, so refresh/revoke cannot invert it.
        lockSecurityEpoch();
        if (tokenType == null) {
            OAuth2Authorization refreshed = findByRefreshToken(token);
            if (refreshed != null) {
                return refreshed;
            }
            List<StoredAuthorization> byCode =
                    jdbc.query(
                            SELECT_BY_CODE_HASH,
                            (rs, index) -> map(rs),
                            OAuthStoreSupport.sha256(token));
            return byCode.isEmpty() ? null : reconstruct(byCode.getFirst(), token);
        }
        if (OAuth2ParameterNames.REFRESH_TOKEN.equals(tokenType.getValue())) {
            return findByRefreshToken(token);
        }
        StoredAuthorization row;
        String codeValue = null;
        if (CODE_TOKEN_TYPE.getValue().equals(tokenType.getValue())) {
            row =
                    jdbc
                            .query(
                                    SELECT_BY_CODE_HASH,
                                    (rs, index) -> map(rs),
                                    OAuthStoreSupport.sha256(token))
                            .stream()
                            .findFirst()
                            .orElse(null);
            codeValue = token;
        } else if (STATE_TOKEN_TYPE.getValue().equals(tokenType.getValue())) {
            row =
                    jdbc.query(SELECT_BY_STATE, (rs, index) -> map(rs), token).stream()
                            .findFirst()
                            .orElse(null);
        } else {
            return null;
        }
        return row == null ? null : reconstruct(row, codeValue);
    }

    private OAuth2Authorization findByRefreshToken(String token) {
        List<RefreshState> rows =
                jdbc.query(
                        SELECT_REFRESH,
                        (rs, index) ->
                                new RefreshState(
                                        rs.getString("family_id"),
                                        rs.getLong("generation"),
                                        OAuthStoreSupport.toInstant(
                                                rs.getObject("issued_at", LocalDateTime.class)),
                                        OAuthStoreSupport.toInstant(
                                                rs.getObject("expires_at", LocalDateTime.class)),
                                        rs.getObject("consumed_at", LocalDateTime.class),
                                        rs.getString("token_status"),
                                        rs.getString("authorization_id"),
                                        rs.getString("family_status"),
                                        OAuthStoreSupport.toInstant(
                                                rs.getObject(
                                                        "absolute_expires_at",
                                                        LocalDateTime.class)),
                                        OAuthStoreSupport.toInstant(
                                                rs.getObject(
                                                        "idle_expires_at", LocalDateTime.class)),
                                        rs.getLong("family_auth_version")),
                        OAuthStoreSupport.sha256(token));
        if (rows.isEmpty()) {
            return null;
        }
        RefreshState state = rows.getFirst();
        Instant now = Instant.now();
        if (!"ACTIVE".equals(state.tokenStatus())) {
            revokeFamily(state.familyId(), "REFRESH_REPLAY");
            return null;
        }
        if (!"ACTIVE".equals(state.familyStatus())
                || now.isAfter(state.tokenExpiresAt())
                || now.isAfter(state.idleExpiresAt())
                || now.isAfter(state.absoluteExpiresAt())) {
            revokeFamily(state.familyId(), "FAMILY_INACTIVE");
            return null;
        }
        StoredAuthorization authorization =
                jdbc.query(SELECT_BY_ID, (rs, row) -> map(rs), state.authorizationId()).stream()
                        .findFirst()
                        .orElse(null);
        if (authorization == null
                || authorization.familyId() == null
                || !state.familyId().equals(authorization.familyId())
                || authorization.authVersion() != state.familyAuthVersion()) {
            revokeFamily(state.familyId(), "AUTHORIZATION_MISMATCH");
            return null;
        }
        return reconstruct(authorization, null, token, state.issuedAt(), state.tokenExpiresAt());
    }

    @Override
    @Transactional
    public void remove(OAuth2Authorization authorization) {
        if (authorization == null || !StringUtils.hasText(authorization.getId())) {
            return;
        }
        lockSecurityEpoch();
        List<String> families =
                jdbc.queryForList(SELECT_AUTHORIZATION_FAMILY, String.class, authorization.getId());
        if (!families.isEmpty() && families.getFirst() != null) {
            revokeFamily(families.getFirst(), "REMOVED");
            return;
        }
        jdbc.update(DELETE, authorization.getId());
    }

    private OAuth2Authorization reconstruct(StoredAuthorization row, String codeValue) {
        return reconstruct(row, codeValue, null, null, null);
    }

    private OAuth2Authorization reconstruct(
            StoredAuthorization row,
            String codeValue,
            String refreshValue,
            Instant refreshIssuedAt,
            Instant refreshExpiresAt) {
        if (row.userId() == null) {
            return null;
        }
        try {
            UserSecurityStore.requireReady(
                    new UserSecurityStore(jdbc.getDataSource()).read(row.userId()),
                    row.authVersion());
        } catch (DomainException stale) {
            return null;
        }
        RegisteredClient client = registeredClients.findById(row.registeredClientId());
        if (client == null) {
            return null;
        }
        Set<String> scopes = readScopes(row);
        if (scopes.isEmpty()) {
            return null;
        }
        AsPrincipal account =
                new AsPrincipal(
                        row.userId() == null ? 0L : row.userId(),
                        row.principalName(),
                        row.displayName(),
                        row.authVersion());
        Authentication principal =
                UsernamePasswordAuthenticationToken.authenticated(
                        account, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        Map<String, Object> additional = new LinkedHashMap<>();
        String resource = storedString(row.attributes(), "resource");
        if (resource != null) {
            additional.put(AuthorizationRequestPolicy.RESOURCE_PARAMETER, resource);
        }
        if (row.pkceChallenge() != null) {
            additional.put(PkceParameterNames.CODE_CHALLENGE, row.pkceChallenge());
        }
        if (row.pkceMethod() != null) {
            additional.put(PkceParameterNames.CODE_CHALLENGE_METHOD, row.pkceMethod());
        }
        OAuth2AuthorizationRequest.Builder requestBuilder =
                OAuth2AuthorizationRequest.authorizationCode()
                        .authorizationUri(properties.issuer() + "/oauth2/authorize")
                        .clientId(client.getClientId())
                        .redirectUri(row.redirectUri())
                        .scopes(scopes)
                        .additionalParameters(additional);
        if (row.state() != null || row.attributes() != null) {
            String clientState = clientState(row.attributes());
            String requestState = clientState != null ? clientState : row.state();
            if (requestState != null) {
                requestBuilder.state(requestState);
            }
        }
        OAuth2AuthorizationRequest request = requestBuilder.build();
        OAuth2Authorization.Builder builder =
                OAuth2Authorization.withRegisteredClient(client)
                        .id(row.id())
                        .principalName(row.principalName())
                        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                        .authorizedScopes(scopes)
                        .attribute(Principal.class.getName(), principal)
                        .attribute(OAuth2AuthorizationRequest.class.getName(), request)
                        .attribute(OAuthAttributes.AUTH_VERSION, row.authVersion());
        if (row.familyId() != null) {
            builder.attribute(OAuthAttributes.FAMILY_ID, row.familyId());
        }
        if (row.state() != null) {
            builder.attribute(OAuth2ParameterNames.STATE, row.state());
        }
        if (codeValue != null && row.codeHash() != null) {
            Instant expiresAt =
                    row.codeExpiresAt() == null
                            ? OAuthStoreSupport.toInstant(row.createTime()).plusSeconds(300)
                            : OAuthStoreSupport.toInstant(row.codeExpiresAt());
            Instant issuedAt = expiresAt.minusSeconds(300);
            OAuth2AuthorizationCode code =
                    new OAuth2AuthorizationCode(codeValue, issuedAt, expiresAt);
            if (row.consumedAt() != null) {
                builder.token(
                        code,
                        metadata ->
                                metadata.put(
                                        OAuth2Authorization.Token.INVALIDATED_METADATA_NAME, true));
            } else {
                builder.token(code);
            }
        }
        if (refreshValue != null && refreshExpiresAt != null) {
            Instant issuedAt =
                    refreshIssuedAt == null
                            ? refreshExpiresAt.minus(properties.refreshTokenTtl())
                            : refreshIssuedAt;
            builder.token(new OAuth2RefreshToken(refreshValue, issuedAt, refreshExpiresAt));
            builder.attribute(
                    OAuthAttributes.REFRESH_TOKEN_HASH, OAuthStoreSupport.sha256(refreshValue));
        }
        return builder.build();
    }

    private static Set<String> readScopes(StoredAuthorization row) {
        Set<String> scopes = new LinkedHashSet<>();
        if (row.scopesJson() != null && !row.scopesJson().isBlank()) {
            Object parsed = OAuthStoreSupport.JSON.readValue(row.scopesJson(), Object.class);
            if (parsed instanceof List<?> values) {
                values.stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .forEach(scopes::add);
            }
        }
        if (scopes.isEmpty() && row.authorizedScopes() != null) {
            scopes.addAll(OAuthStoreSupport.splitCsv(row.authorizedScopes()));
        }
        return scopes;
    }

    private static Map<String, Object> attributes(
            AsPrincipal account, String familyId, long authVersion) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("user_id", account.userId());
        attributes.put("display_name", account.displayName());
        if (familyId != null) {
            attributes.put("family_id", familyId);
        }
        attributes.put("auth_version", authVersion);
        return attributes;
    }

    private static AsPrincipal requireAccount(Authentication principal, String principalName) {
        if (principal == null) {
            throw new IllegalStateException("Authorization principal is missing");
        }
        Object value = principal.getPrincipal();
        if (value instanceof AsPrincipal account) {
            return account;
        }
        throw new IllegalStateException(
                "Authorization principal is not an AS account: " + principalName);
    }

    /** Only raised before grant writes; its sole persisted effect is family revocation. */
    private static final class RefreshReplayException extends OAuth2AuthenticationException {
        private static final long serialVersionUID = 1L;

        RefreshReplayException() {
            super(OAuth2ErrorCodes.INVALID_GRANT);
        }
    }

    private static long attributeLong(
            OAuth2Authorization authorization, String name, long fallback) {
        Object value = authorization.getAttribute(name);
        return value instanceof Number number ? number.longValue() : fallback;
    }

    private static String stringParameter(Map<String, Object> parameters, String name) {
        Object value = parameters == null ? null : parameters.get(name);
        return value instanceof String text && StringUtils.hasText(text) ? text : null;
    }

    private static Instant earliest(Instant left, Instant right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.isBefore(right) ? left : right;
    }

    private static Instant latest(Instant... candidates) {
        Instant latest = null;
        for (Instant candidate : candidates) {
            if (candidate != null && (latest == null || candidate.isAfter(latest))) {
                latest = candidate;
            }
        }
        return latest;
    }

    private static Instant tokenIssuedAt(OAuth2Authorization.Token<?> token) {
        return token == null ? null : token.getToken().getIssuedAt();
    }

    private static Instant tokenExpiresAt(OAuth2Authorization.Token<?> token) {
        return token == null ? null : token.getToken().getExpiresAt();
    }

    private static LocalDateTime toTimestamp(Instant value) {
        return OAuthStoreSupport.toUtc(value);
    }

    private static OAuth2AuthenticationException invalidGrant() {
        return new OAuth2AuthenticationException(OAuth2ErrorCodes.INVALID_GRANT);
    }

    private static StoredAuthorization map(ResultSet rs) throws SQLException {
        return new StoredAuthorization(
                rs.getString("id"),
                rs.getString("registered_client_id"),
                rs.getString("principal_name"),
                rs.getString("authorized_scopes"),
                rs.getBytes("attributes"),
                rs.getString("state"),
                rs.getBytes("authorization_code_hash"),
                rs.getObject("code_expires_at", LocalDateTime.class),
                rs.getObject("code_consumed_at", LocalDateTime.class),
                rs.getString("redirect_uri"),
                rs.getString("pkce_challenge"),
                rs.getString("pkce_method"),
                rs.getString("scopes"),
                rs.getObject("user_id", Long.class),
                rs.getString("family_id"),
                rs.getLong("auth_version"),
                rs.getObject("create_time", LocalDateTime.class),
                displayName(rs.getBytes("attributes")));
    }

    private static String clientState(byte[] attributes) {
        return storedString(attributes, OAuthAttributes.CLIENT_STATE);
    }

    private static String storedString(byte[] attributes, String name) {
        if (attributes == null || attributes.length == 0) {
            return null;
        }
        Map<?, ?> values =
                OAuthStoreSupport.JSON.readValue(
                        new String(attributes, java.nio.charset.StandardCharsets.UTF_8), Map.class);
        Object value = values.get(name);
        return value instanceof String text ? text : null;
    }

    private static String displayName(byte[] attributes) {
        if (attributes == null || attributes.length == 0) {
            return null;
        }
        Map<?, ?> values =
                OAuthStoreSupport.JSON.readValue(
                        new String(attributes, java.nio.charset.StandardCharsets.UTF_8), Map.class);
        Object displayName = values.get("display_name");
        return displayName instanceof String text ? text : null;
    }

    private record ActiveRefresh(byte[] tokenHash, long generation) {
        private ActiveRefresh {
            tokenHash = tokenHash.clone();
        }

        @Override
        public byte[] tokenHash() {
            return tokenHash.clone();
        }
    }

    private record FamilyState(String status, Instant absoluteExpiresAt, Instant idleExpiresAt) {}

    private record RefreshState(
            String familyId,
            long generation,
            Instant issuedAt,
            Instant tokenExpiresAt,
            LocalDateTime consumedAt,
            String tokenStatus,
            String authorizationId,
            String familyStatus,
            Instant absoluteExpiresAt,
            Instant idleExpiresAt,
            long familyAuthVersion) {}

    private record StoredAuthorization(
            String id,
            String registeredClientId,
            String principalName,
            String authorizedScopes,
            byte[] attributes,
            String state,
            byte[] codeHash,
            LocalDateTime codeExpiresAt,
            LocalDateTime consumedAt,
            String redirectUri,
            String pkceChallenge,
            String pkceMethod,
            String scopesJson,
            Long userId,
            String familyId,
            long authVersion,
            LocalDateTime createTime,
            String displayName) {
        StoredAuthorization {
            attributes = attributes == null ? null : attributes.clone();
            codeHash = codeHash == null ? null : codeHash.clone();
        }

        @Override
        public byte[] attributes() {
            return attributes == null ? null : attributes.clone();
        }

        @Override
        public byte[] codeHash() {
            return codeHash == null ? null : codeHash.clone();
        }
    }
}
