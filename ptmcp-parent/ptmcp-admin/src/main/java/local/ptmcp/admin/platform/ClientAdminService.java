package local.ptmcp.admin.platform;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.sql.DataSource;

/**
 * OAuth client management for the admin console: the first production writer of {@code
 * oauth2_registered_client}. Random secrets are high-entropy, shown once in the create response and
 * stored only as the Spring-compatible bcrypt encoding plus a SHA-256 digest for out-of-band
 * verification (never the raw value, and no read path returns it). Callback or authentication
 * changes and deletes revoke the client's refresh families in the same transaction, so an edited
 * client cannot keep inherited grants.
 */
public final class ClientAdminService {
    /** Scope vocabulary mirrors the gateway's accepted MCP scopes. */
    private static final Set<String> ALLOWED_SCOPES =
            Set.of("mcp:catalog", "mcp:schema", "mcp:query");

    private static final Set<String> AUTH_METHODS =
            Set.of("none", "client_secret_basic", "client_secret_post", "private_key_jwt");
    private static final Set<String> SECRET_METHODS =
            Set.of("client_secret_basic", "client_secret_post");
    private static final int MAX_REDIRECT_URIS = 10;
    private static final int MAX_SCOPES = 8;
    private static final Pattern CLIENT_NAME = Pattern.compile("^.{1,200}$");
    private static final PasswordEncoder PASSWORDS =
            PasswordEncoderFactories.createDelegatingPasswordEncoder();
    private static final String GRANT_TYPES = "authorization_code,refresh_token";
    private static final String CLIENT_SETTINGS = "{}";
    private static final String TOKEN_SETTINGS = "{}";

    private final DataSource primary;
    private final SecureRandom random = new SecureRandom();

    public ClientAdminService(DataSource primary) {
        this.primary = Objects.requireNonNull(primary, "primary");
    }

    public record ClientWrite(
            String name,
            String type,
            List<String> authMethods,
            List<String> redirectUris,
            List<String> allowedScopes,
            String jwksUri,
            String status) {}

    public record ClientView(
            String id,
            String clientId,
            String platformId,
            String name,
            String type,
            List<String> authMethods,
            List<String> redirectUris,
            List<String> allowedScopes,
            String jwksUri,
            String status,
            boolean secretConfigured,
            String version) {}

    /**
     * Create response: the raw secret appears exactly here and is never stored or returned again.
     */
    public record CreatedClient(ClientView client, String clientSecret) {}

    public CreatedClient create(
            long platformId, ClientWrite write, long actorId, String requestId) {
        requireActor(actorId);
        Normalized normalized = normalize(write);
        if (!"PUBLIC".equals(normalized.type()) && !"CONFIDENTIAL".equals(normalized.type())) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        boolean confidential = "CONFIDENTIAL".equals(normalized.type());
        if (!confidential && !normalized.authMethods().equals(List.of("none"))) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (confidential && normalized.authMethods().contains("none")) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        String rawSecret = null;
        String storedSecret = null;
        byte[] secretHash = null;
        if (normalized.authMethods().stream().anyMatch(SECRET_METHODS::contains)) {
            rawSecret = generateSecret();
            storedSecret = PASSWORDS.encode(rawSecret);
            secretHash = sha256(rawSecret);
        } else if (confidential) {
            // private_key_jwt clients prove via JWKS; jwksUri is mandatory.
            if (normalized.jwksUri() == null || normalized.jwksUri().isBlank()) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
            requireHttps(normalized.jwksUri());
        }
        String id = UUID.randomUUID().toString();
        String clientId = "mcp-" + token(8).toLowerCase(Locale.ROOT);
        try (Connection connection = tx()) {
            try {
                requirePlatformLive(connection, platformId);
                try (PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO oauth2_registered_client (id, client_id,"
                                        + " client_secret, client_name, client_authentication_methods,"
                                        + " authorization_grant_types, redirect_uris, scopes,"
                                        + " client_settings, token_settings, platform_id, client_type,"
                                        + " client_secret_hash, jwks_uri, status, registration_source,"
                                        + " version, create_time, update_time, created_by, updated_by)"
                                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ADMIN',"
                                        + " 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), ?, ?)")) {
                    statement.setString(1, id);
                    statement.setString(2, clientId);
                    if (storedSecret != null) {
                        statement.setString(3, storedSecret);
                        statement.setBytes(13, secretHash);
                    } else {
                        statement.setNull(3, java.sql.Types.VARCHAR);
                        statement.setNull(13, java.sql.Types.BINARY);
                    }
                    statement.setString(4, normalized.name());
                    statement.setString(5, String.join(",", normalized.authMethods()));
                    statement.setString(6, GRANT_TYPES);
                    statement.setString(
                            7,
                            normalized.redirectUris().isEmpty()
                                    ? null
                                    : String.join("\n", normalized.redirectUris()));
                    statement.setString(8, String.join(" ", normalized.allowedScopes()));
                    statement.setString(9, CLIENT_SETTINGS);
                    statement.setString(10, TOKEN_SETTINGS);
                    statement.setLong(11, platformId);
                    statement.setString(12, normalized.type());
                    statement.setString(14, normalized.jwksUri());
                    statement.setString(15, write.status());
                    statement.setLong(16, actorId);
                    statement.setLong(17, actorId);
                    statement.executeUpdate();
                }
                log(
                        connection,
                        actorId,
                        requestId,
                        "CLIENT_CREATE",
                        id,
                        null,
                        summary(normalized, write.status(), secretHash != null));
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        ClientView view = viewOf(platformId, id);
        if (view == null) {
            throw new DomainException(ErrorCode.OPERATION_UNKNOWN);
        }
        return new CreatedClient(view, rawSecret);
    }

    public List<ClientView> list(
            long platformId, String type, String status, String cursor, Integer pageSize) {
        int limit = pageSize == null ? 20 : pageSize;
        if (limit < 1 || limit > 100) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        long afterVersion = 0;
        if (cursor != null && !cursor.isBlank()) {
            try {
                afterVersion = Long.parseLong(cursor);
            } catch (NumberFormatException failure) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
        }
        StringBuilder sql =
                new StringBuilder(
                        "SELECT id, client_id, platform_id, client_name, client_type,"
                                + " client_authentication_methods, redirect_uris, scopes, jwks_uri,"
                                + " status, client_secret IS NOT NULL OR client_secret_hash IS NOT NULL,"
                                + " version FROM oauth2_registered_client WHERE platform_id = ?");
        List<Object> parameters = new ArrayList<>();
        parameters.add(platformId);
        if (type != null && !type.isBlank()) {
            if (!Set.of("PUBLIC", "CONFIDENTIAL").contains(type)) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
            sql.append(" AND client_type = ?");
            parameters.add(type);
        }
        if (status != null && !status.isBlank()) {
            if (!Set.of("ACTIVE", "DISABLED").contains(status)) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
            sql.append(" AND status = ?");
            parameters.add(status);
        }
        sql.append(" AND version > ? ORDER BY version LIMIT ?");
        parameters.add(afterVersion);
        parameters.add(limit + 1);
        // Returns up to limit+1 rows; the caller derives the cursor when the page overflows.
        List<ClientView> items = new ArrayList<>();
        try (Connection connection = tx()) {
            connection.rollback();
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                for (int index = 0; index < parameters.size(); index++) {
                    statement.setObject(index + 1, parameters.get(index));
                }
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        long version = result.getLong(12);
                        items.add(
                                new ClientView(
                                        result.getString(1),
                                        result.getString(2),
                                        Long.toString(result.getLong(3)),
                                        result.getString(4),
                                        result.getString(5),
                                        List.of(result.getString(6).split(",")),
                                        splitLines(result.getString(7)),
                                        List.of(result.getString(8).split(" ")),
                                        result.getString(9),
                                        result.getString(10),
                                        result.getBoolean(11),
                                        Long.toString(version)));
                    }
                }
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        return items;
    }

    public ClientView get(String id) {
        ClientView view = viewRow(id);
        if (view == null) {
            throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return view;
    }

    /**
     * Callback, scope or status changes are security state: the row version bumps and every ACTIVE
     * refresh family of the client is revoked inside the same transaction.
     */
    public ClientView update(
            String id, long expectedVersion, ClientWrite write, long actorId, String requestId) {
        requireActor(actorId);
        if (expectedVersion <= 0) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        Normalized normalized = normalize(write);
        try (Connection connection = tx()) {
            try {
                Current current = current(connection, id);
                if (!current.type().equals(normalized.type())
                        || !current.authMethods().equals(normalized.authMethods())) {
                    throw new DomainException(ErrorCode.UNSUPPORTED_FIELD);
                }
                try (PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE oauth2_registered_client SET client_name = ?, redirect_uris = ?,"
                                        + " scopes = ?, jwks_uri = ?, status = ?, version = version + 1,"
                                        + " update_time = UTC_TIMESTAMP(6), updated_by = ?"
                                        + " WHERE id = ? AND version = ?")) {
                    statement.setString(1, normalized.name());
                    statement.setString(
                            2,
                            normalized.redirectUris().isEmpty()
                                    ? null
                                    : String.join("\n", normalized.redirectUris()));
                    statement.setString(3, String.join(" ", normalized.allowedScopes()));
                    statement.setString(4, normalized.jwksUri());
                    statement.setString(5, write.status());
                    statement.setLong(6, actorId);
                    statement.setString(7, id);
                    statement.setLong(8, expectedVersion);
                    if (statement.executeUpdate() != 1) {
                        throw new DomainException(ErrorCode.VERSION_CONFLICT);
                    }
                }
                int revoked = revokeFamilies(connection, id, "client updated via console");
                log(
                        connection,
                        actorId,
                        requestId,
                        "CLIENT_UPDATE",
                        id,
                        summary(current.normalized(), current.status(), current.secretConfigured()),
                        withRevokedFamilies(
                                summary(normalized, write.status(), current.secretConfigured()),
                                revoked));
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        ClientView view = viewRow(id);
        if (view == null) {
            throw new DomainException(ErrorCode.OPERATION_UNKNOWN);
        }
        return view;
    }

    /** Disable + revoke families; the row stays for audit and its id is never reused. */
    public ClientView delete(String id, long expectedVersion, long actorId, String requestId) {
        requireActor(actorId);
        if (expectedVersion <= 0) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        try (Connection connection = tx()) {
            try {
                Current current = current(connection, id);
                try (PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE oauth2_registered_client SET status = 'DISABLED',"
                                        + " version = version + 1, update_time = UTC_TIMESTAMP(6),"
                                        + " updated_by = ? WHERE id = ? AND version = ?")) {
                    statement.setLong(1, actorId);
                    statement.setString(2, id);
                    statement.setLong(3, expectedVersion);
                    if (statement.executeUpdate() != 1) {
                        throw new DomainException(ErrorCode.VERSION_CONFLICT);
                    }
                }
                int revoked = revokeFamilies(connection, id, "client deleted via console");
                log(
                        connection,
                        actorId,
                        requestId,
                        "CLIENT_DELETE",
                        id,
                        summary(current.normalized(), current.status(), current.secretConfigured()),
                        "{\"status\":\"DISABLED\",\"revokedFamilies\":" + revoked + "}");
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        return get(id);
    }

    // ---- internals ---------------------------------------------------------

    private record Current(
            String platformId,
            String type,
            List<String> authMethods,
            String status,
            boolean secretConfigured,
            Normalized normalized) {}

    private record Normalized(
            String name,
            String type,
            List<String> authMethods,
            List<String> redirectUris,
            List<String> allowedScopes,
            String jwksUri) {}

    private record ClientRow(
            String platformId,
            String name,
            String type,
            String authMethods,
            String redirectUris,
            String scopes,
            String jwksUri,
            String status,
            boolean secretConfigured,
            long version) {}

    private Current current(Connection connection, String id) throws SQLException {
        ClientRow row = rowById(connection, id);
        if (row == null) {
            throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        List<String> authMethods = List.of(row.authMethods().split(","));
        Normalized normalized =
                new Normalized(
                        row.name(),
                        row.type(),
                        authMethods,
                        splitLines(row.redirectUris()),
                        List.of(row.scopes().split(" ")),
                        row.jwksUri());
        return new Current(
                row.platformId(),
                row.type(),
                authMethods,
                row.status(),
                row.secretConfigured(),
                normalized);
    }

    private ClientRow rowById(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT platform_id, client_name, client_type, client_authentication_methods,"
                                + " redirect_uris, scopes, jwks_uri, status,"
                                + " client_secret IS NOT NULL OR client_secret_hash IS NOT NULL, version"
                                + " FROM oauth2_registered_client WHERE id = ?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new ClientRow(
                        Long.toString(result.getLong(1)),
                        result.getString(2),
                        result.getString(3),
                        result.getString(4),
                        result.getString(5),
                        result.getString(6),
                        result.getString(7),
                        result.getString(8),
                        result.getBoolean(9),
                        result.getLong(10));
            }
        }
    }

    private ClientView viewRow(String id) {
        try (Connection connection = tx()) {
            connection.rollback();
            ClientRow row = rowById(connection, id);
            if (row == null) {
                return null;
            }
            return new ClientView(
                    id,
                    clientId(connection, id),
                    row.platformId(),
                    row.name(),
                    row.type(),
                    List.of(row.authMethods().split(",")),
                    splitLines(row.redirectUris()),
                    List.of(row.scopes().split(" ")),
                    row.jwksUri(),
                    row.status(),
                    row.secretConfigured(),
                    Long.toString(row.version()));
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private String clientId(Connection connection, String id) {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT client_id FROM oauth2_registered_client WHERE id = ?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
                }
                return result.getString(1);
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private ClientView viewOf(long platformId, String id) {
        ClientView view = viewRow(id);
        if (view == null) {
            return null;
        }
        return new ClientView(
                view.id(),
                view.clientId(),
                Long.toString(platformId),
                view.name(),
                view.type(),
                view.authMethods(),
                view.redirectUris(),
                view.allowedScopes(),
                view.jwksUri(),
                view.status(),
                view.secretConfigured(),
                view.version());
    }

    private Normalized normalize(ClientWrite write) {
        if (write == null
                || write.name() == null
                || write.type() == null
                || write.status() == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (!CLIENT_NAME.matcher(write.name().trim()).matches()
                || !Set.of("ACTIVE", "DISABLED").contains(write.status())) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        List<String> authMethods =
                write.authMethods() == null
                        ? List.of()
                        : List.copyOf(new LinkedHashSet<>(write.authMethods()));
        if (authMethods.isEmpty() || !AUTH_METHODS.containsAll(authMethods)) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        List<String> redirectUris = write.redirectUris() == null ? List.of() : write.redirectUris();
        if (redirectUris.size() > MAX_REDIRECT_URIS) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        redirectUris.forEach(ClientAdminService::validateRedirectUri);
        List<String> scopes = write.allowedScopes() == null ? List.of() : write.allowedScopes();
        if (scopes.isEmpty()
                || scopes.size() > MAX_SCOPES
                || !ALLOWED_SCOPES.containsAll(new LinkedHashSet<>(scopes))) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (write.jwksUri() != null
                && !write.jwksUri().isBlank()
                && write.jwksUri().length() > 2048) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        return new Normalized(
                write.name().trim(),
                write.type(),
                authMethods,
                List.copyOf(redirectUris),
                List.copyOf(new LinkedHashSet<>(scopes)),
                write.jwksUri() == null || write.jwksUri().isBlank()
                        ? null
                        : write.jwksUri().trim());
    }

    /** Same callback rules as DCR: https or loopback http, no wildcards, userinfo or fragments. */
    private static void validateRedirectUri(String value) {
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException failure) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (value.indexOf('*') >= 0
                || uri.getScheme() == null
                || uri.getHost() == null
                || uri.getHost().isEmpty()
                || uri.getUserInfo() != null
                || uri.getFragment() != null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        boolean https = "https".equals(scheme);
        boolean loopbackHttp =
                "http".equals(scheme)
                        && ("localhost".equalsIgnoreCase(uri.getHost())
                                || uri.getHost().startsWith("127."));
        if (!https && !loopbackHttp) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        String path = uri.getRawPath();
        if (path == null || !path.startsWith("/") || path.contains("..")) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
    }

    private static void requireHttps(String value) {
        try {
            URI uri = new URI(value);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
        } catch (URISyntaxException failure) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
    }

    private void requirePlatformLive(Connection connection, long platformId) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "SELECT 1 FROM platform_registry WHERE id = ? AND status <> 'DELETED'")) {
            statement.setLong(1, platformId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
                }
            }
        }
    }

    private int revokeFamilies(Connection connection, String clientId, String reason)
            throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "UPDATE oauth_refresh_family SET status = 'REVOKED', revoked_at = UTC_TIMESTAMP(6),"
                                + " revoke_reason = ?, version = version + 1, update_time = UTC_TIMESTAMP(6)"
                                + " WHERE registered_client_id = ? AND status = 'ACTIVE'")) {
            statement.setString(1, reason);
            statement.setString(2, clientId);
            return statement.executeUpdate();
        }
    }

    private void log(
            Connection connection,
            long actorId,
            String requestId,
            String action,
            String aggregateId,
            String beforeJson,
            String afterJson)
            throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement(
                        "INSERT INTO admin_operation_log (actor_id, request_id, action,"
                                + " aggregate_type, aggregate_id, before_masked, after_masked,"
                                + " occurred_at, create_time)"
                                + " VALUES (?, ?, ?, 'OAUTH_CLIENT', ?, ?, ?, UTC_TIMESTAMP(6),"
                                + " UTC_TIMESTAMP(6))")) {
            statement.setLong(1, actorId);
            statement.setString(2, requestId);
            statement.setString(3, action);
            statement.setString(4, aggregateId);
            statement.setString(5, beforeJson);
            statement.setString(6, afterJson);
            statement.executeUpdate();
        }
    }

    private static String summary(Normalized client, String status, boolean secretConfigured) {
        return "{\"name\":\""
                + escape(client.name())
                + "\",\"type\":\""
                + client.type()
                + "\",\"authMethods\":"
                + stringListJson(client.authMethods())
                + ",\"redirectUris\":"
                + stringListJson(client.redirectUris())
                + ",\"scopes\":"
                + stringListJson(client.allowedScopes())
                + ",\"status\":\""
                + status
                + "\",\"secretConfigured\":"
                + secretConfigured
                + "}";
    }

    private static String withRevokedFamilies(String summaryJson, int revoked) {
        return summaryJson.substring(0, summaryJson.length() - 1)
                + ",\"revokedFamilies\":"
                + revoked
                + "}";
    }

    /** Whitelisted vocabulary only, so quoting the values is enough for valid JSON. */
    private static String stringListJson(List<String> values) {
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append('"').append(values.get(index)).append('"');
        }
        return json.append(']').toString();
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static List<String> splitLines(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value.split("\n"));
    }

    private String generateSecret() {
        return "ptmcp_" + token(32);
    }

    private String token(int bytes) {
        byte[] raw = new byte[bytes];
        random.nextBytes(raw);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException failure) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private static void requireActor(long actorId) {
        if (actorId <= 0) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
    }

    private Connection tx() throws SQLException {
        Connection connection = primary.getConnection();
        connection.setAutoCommit(false);
        return connection;
    }
}
