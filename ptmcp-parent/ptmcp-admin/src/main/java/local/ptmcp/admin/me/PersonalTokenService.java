package local.ptmcp.admin.me;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.security.UserSecurityStore;

import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import javax.sql.DataSource;

/**
 * Personal access tokens for the signed-in user. The plaintext is returned exactly once at
 * creation; only a SHA-256 digest and a short display prefix are persisted, so a lost response
 * requires revoking the token and creating a new intent. Tokens bind a Tier 3 platform and can only
 * carry the three MCP API scopes.
 */
public final class PersonalTokenService {
    private static final String TOKEN_PREFIX = "ptmcp_pat_";
    private static final int DISPLAY_PREFIX_LENGTH = 16;
    private static final int MAX_TOKEN_DAYS = 90;
    private static final int DEFAULT_TOKEN_DAYS = 30;
    private static final Set<String> SCOPES = Set.of("mcp:catalog", "mcp:schema", "mcp:query");
    private static final ZoneOffset UTC = ZoneOffset.UTC;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final DataSource primary;
    private final SecureRandom random = new SecureRandom();

    public PersonalTokenService(DataSource primary) {
        this.primary = Objects.requireNonNull(primary, "primary");
    }

    public record Created(String token, TokenView metadata) {}

    public record TokenView(
            long id,
            String name,
            String prefix,
            List<String> scopes,
            Long platformId,
            Instant expiresAt,
            Instant lastUsedAt,
            Instant createdAt,
            String status) {}

    public record TokenPage(List<TokenView> items, String nextCursor) {}

    public Created create(
            long userId, long expectedAuthVersion, String name, Long platformId, List<String> scopes, Instant expiresAt) {
        if (name == null || name.isBlank() || name.length() > 64 || platformId == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        requireTierThreePlatform(platformId);
        List<String> requested = normalizedScopes(scopes);
        Instant now = Instant.now();
        Instant expiry =
                expiresAt == null ? now.plus(Duration.ofDays(DEFAULT_TOKEN_DAYS)) : expiresAt;
        if (!expiry.isAfter(now) || expiry.isAfter(now.plus(Duration.ofDays(MAX_TOKEN_DAYS)))) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        String token = TOKEN_PREFIX + newToken();
        String prefix = token.substring(0, Math.min(DISPLAY_PREFIX_LENGTH, token.length()));
        byte[] hash = sha256(token.getBytes(StandardCharsets.UTF_8));
        try (Connection connection = primary.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO personal_token (user_id, platform_id, token_prefix,"
                                        + " token_hash, name, scopes, auth_version, expires_at,"
                                        + " status, version, create_time, update_time, created_by,"
                                        + " updated_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE',"
                                        + " 1, ?, ?, ?, ?)",
                                PreparedStatement.RETURN_GENERATED_KEYS)) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            UserSecurityStore.lockGlobal(connection);
            UserSecurityStore.requireReady(UserSecurityStore.read(connection, userId, true), expectedAuthVersion);
            UserSecurityStore.requireActiveProfile(connection, userId);
            statement.setLong(1, userId);
            statement.setLong(2, platformId);
            statement.setString(3, prefix);
            statement.setBytes(4, hash);
            statement.setString(5, name.trim());
            statement.setString(6, JSON.writeValueAsString(requested));
            statement.setLong(7, expectedAuthVersion);
            statement.setObject(8, utc(expiry));
            statement.setObject(9, utc(now));
            statement.setObject(10, utc(now));
            statement.setLong(11, userId);
            statement.setLong(12, userId);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
                }
                long createdId = keys.getLong(1);
                connection.commit();
                return new Created(
                        token,
                        new TokenView(
                                createdId,
                                name.trim(),
                                prefix,
                                requested,
                                platformId,
                                expiry,
                                null,
                                now,
                                "ACTIVE"));
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    public TokenPage list(long userId, String status, String cursor, Integer pageSize) {
        int limit = pageSize == null ? 20 : pageSize;
        if (limit < 1 || limit > 100) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        long after = cursor == null || cursor.isBlank() ? 0 : parseCursor(cursor);
        String normalizedStatus = status == null || status.isBlank() ? null : status.trim();
        if (normalizedStatus != null
                && !normalizedStatus.equals("ACTIVE")
                && !normalizedStatus.equals("REVOKED")
                && !normalizedStatus.equals("EXPIRED")) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        String sql =
                "SELECT id, name, token_prefix, scopes, platform_id, expires_at, last_used_at,"
                        + " create_time, status FROM personal_token WHERE user_id = ? AND id > ?"
                        + (normalizedStatus == null ? "" : " AND status = ?")
                        + " ORDER BY id LIMIT ?";
        List<TokenView> items = new ArrayList<>();
        try (Connection connection = primary.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            statement.setLong(index++, userId);
            statement.setLong(index++, after);
            if (normalizedStatus != null) {
                statement.setString(index++, normalizedStatus);
            }
            statement.setInt(index, limit + 1);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    items.add(tokenView(result));
                }
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        String next = null;
        if (items.size() > limit) {
            items = new ArrayList<>(items.subList(0, limit));
            next = Long.toString(items.get(items.size() - 1).id());
        }
        return new TokenPage(items, next);
    }

    /** Owned ids are revoked idempotently; any other id is indistinguishable from missing. */
    public TokenView revoke(long userId, long tokenId) {
        if (tokenId <= 0) {
            throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        Instant now = Instant.now();
        try (Connection connection = primary.getConnection()) {
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "UPDATE personal_token SET status = 'REVOKED', revoked_at = ?,"
                                    + " version = version + 1, update_time = ?"
                                    + " WHERE id = ? AND user_id = ? AND status = 'ACTIVE'")) {
                statement.setObject(1, utc(now));
                statement.setObject(2, utc(now));
                statement.setLong(3, tokenId);
                statement.setLong(4, userId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement =
                    connection.prepareStatement(
                            "SELECT id, name, token_prefix, scopes, platform_id, expires_at,"
                                    + " last_used_at, create_time, status FROM personal_token"
                                    + " WHERE id = ? AND user_id = ?")) {
                statement.setLong(1, tokenId);
                statement.setLong(2, userId);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
                    }
                    return tokenView(result);
                }
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private void requireTierThreePlatform(long platformId) {
        try (Connection connection = primary.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT tier, status FROM platform_registry WHERE id = ?")) {
            statement.setLong(1, platformId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !"ACTIVE".equals(result.getString(2))) {
                    throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
                }
                if (result.getInt(1) != 3) {
                    throw new DomainException(ErrorCode.MANAGEMENT_SCOPE_DENIED);
                }
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private static List<String> normalizedScopes(List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String scope : scopes) {
            if (scope == null || !SCOPES.contains(scope.trim())) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
            unique.add(scope.trim());
        }
        return List.copyOf(unique);
    }

    private static TokenView tokenView(ResultSet result) throws SQLException {
        List<String> scopes = new ArrayList<>();
        String json = result.getString(4);
        if (json != null) {
            for (Object value : JSON.readValue(json, List.class)) {
                scopes.add(String.valueOf(value));
            }
        }
        long platformId = result.getLong(5);
        return new TokenView(
                result.getLong(1),
                result.getString(2),
                result.getString(3),
                List.copyOf(scopes),
                result.wasNull() ? null : platformId,
                toInstant(result.getObject(6, LocalDateTime.class)),
                toInstant(result.getObject(7, LocalDateTime.class)),
                toInstant(result.getObject(8, LocalDateTime.class)),
                result.getString(9));
    }

    private static long parseCursor(String cursor) {
        try {
            long parsed = Long.parseLong(cursor.trim());
            if (parsed < 0) {
                throw new NumberFormatException("negative");
            }
            return parsed;
        } catch (NumberFormatException failure) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
    }

    private String newToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, UTC);
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(UTC);
    }
}
