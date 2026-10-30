package local.ptmcp.admin.me;

import local.ptmcp.admin.audit.AuditQueryService;
import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.org.AclDirectory;
import local.ptmcp.persistence.org.ResourceIds;

import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

/**
 * Read-side personal portal queries. Every method takes the user id resolved from the session
 * server-side; nothing is accepted from query parameters. Visibility always runs through {@link
 * AclDirectory} so the platform scope remains the ceiling and explicit META denies hide objects.
 */
public final class MeService {
    private static final int MAX_PAGE = 100;
    private static final int DEFAULT_PAGE = 20;
    private static final ZoneOffset UTC = ZoneOffset.UTC;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> CLIENT_TYPES =
            Set.of("GENERIC", "WORKBUDDY", "CURSOR", "CLAUDE_CODE", "VSCODE");

    private final DataSource primary;
    private final AclDirectory directory;
    private final String mcpUrl;
    private final AuditQueryService audits;

    public MeService(
            DataSource primary, AclDirectory directory, String mcpUrl, AuditQueryService audits) {
        this.primary = Objects.requireNonNull(primary, "primary");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.mcpUrl = mcpUrl == null || mcpUrl.isBlank() ? null : mcpUrl.trim();
        this.audits = Objects.requireNonNull(audits, "audits");
    }

    public record PlatformItem(String type, long id, String name, int tier, String catalogMode) {}

    public record DatasourceItem(String type, long id, String name, String engine) {}

    public record NamespaceItem(
            String type, long id, String databaseName, String schemaName, String displayName) {}

    public record AccessContext(
            List<Object> items,
            String nextCursor,
            Instant evaluatedAt,
            Map<String, String> versions) {}

    public record ObjectSummary(
            long objectId,
            String databaseName,
            String schemaName,
            String tableName,
            String objectType,
            String tableComment,
            boolean authorized,
            boolean metadataVisible) {}

    public record ObjectPage(List<ObjectSummary> items, String nextCursor) {}

    public record ColumnView(
            int ordinal,
            String name,
            String databaseType,
            int jdbcType,
            boolean nullable,
            Integer precision,
            Integer scale,
            String comment,
            boolean primaryKey,
            String title,
            String description) {}

    public record ObjectDetail(
            long objectId,
            String databaseName,
            String schemaName,
            String tableName,
            String objectType,
            String tableComment,
            String dictionaryTitle,
            String dictionaryDescription,
            long snapshotVersion,
            long generation,
            boolean authorized,
            boolean metadataVisible,
            List<ColumnView> columns,
            List<Object> foreignKeys,
            boolean foreignKeysRestricted) {}

    public record McpConfig(
            String transport,
            String url,
            List<String> authentication,
            String configTemplate,
            String compatibilityStatus,
            String validatedVersion) {}

    public record AuthorizationView(
            String id,
            String clientName,
            Long platformId,
            Instant issuedAt,
            Instant lastUsedAt,
            String status,
            Instant absoluteExpiresAt) {}

    public record AuthorizationPage(List<AuthorizationView> items, String nextCursor) {}

    public record AuthorizationRevoked(String id, String status, boolean revoked) {}

    private record PlatformRow(
            long id,
            String name,
            int tier,
            String catalogMode,
            String scopeVersion,
            String securityVersion) {}

    private record DatasourceRow(
            long id, String name, String engine, Long activeSnapshotId, long resourceVersion) {}

    private record NamespaceRow(long id, long datasourceId, String catalog, String schema) {}

    private record ObjectRow(
            long id,
            long namespaceId,
            long datasourceId,
            String catalog,
            String schema,
            String objectName,
            String kind,
            String tableComment,
            long generation) {}

    // ------------------------------------------------------------------ access context

    public AccessContext accessContext(
            long userId,
            String level,
            Long platformId,
            Long datasourceId,
            String cursor,
            Integer pageSize) {
        String normalized = level == null ? "" : level.trim().toUpperCase(Locale.ROOT);
        int limit = pageSize(pageSize);
        long after = cursorId(cursor);
        Instant now = Instant.now();
        List<Object> items = new ArrayList<>();
        String next;
        Map<String, String> versions = new LinkedHashMap<>();
        switch (normalized) {
            case "PLATFORM" -> {
                List<DatasourceRow> datasources = activeDatasources();
                List<NamespaceRow> namespaces = namespaces();
                for (PlatformRow platform : activePlatforms()) {
                    if (platform.id() <= after) {
                        continue;
                    }
                    if (visibleDatasources(
                                    userId,
                                    platform.id(),
                                    discover(platform),
                                    datasources,
                                    namespaces,
                                    now)
                            .isEmpty()) {
                        continue;
                    }
                    items.add(
                            new PlatformItem(
                                    "PLATFORM",
                                    platform.id(),
                                    platform.name(),
                                    platform.tier(),
                                    platform.catalogMode()));
                    versions.put(
                            "platform:" + platform.id(),
                            platform.scopeVersion() + "/" + platform.securityVersion());
                }
                next = paginate(items, limit, item -> ((PlatformItem) item).id());
            }
            case "DATASOURCE" -> {
                PlatformRow platform = requirePlatform(platformId);
                List<DatasourceRow> datasources = activeDatasources();
                List<NamespaceRow> namespaces = namespaces();
                Set<Long> visible =
                        visibleDatasources(
                                userId,
                                platform.id(),
                                discover(platform),
                                datasources,
                                namespaces,
                                now);
                for (DatasourceRow datasource : datasources) {
                    if (datasource.id() > after && visible.contains(datasource.id())) {
                        items.add(
                                new DatasourceItem(
                                        "DATASOURCE",
                                        datasource.id(),
                                        datasource.name(),
                                        datasource.engine()));
                    }
                }
                versions.put(
                        "platform:" + platform.id(),
                        platform.scopeVersion() + "/" + platform.securityVersion());
                next = paginate(items, limit, item -> ((DatasourceItem) item).id());
            }
            case "NAMESPACE" -> {
                PlatformRow platform = requirePlatform(platformId);
                DatasourceRow datasource = requireDatasource(datasourceId);
                List<NamespaceRow> namespaces = namespacesOf(datasource.id());
                Map<Long, AclDirectory.ResourceDecision> decisions =
                        decisions(
                                userId,
                                platform.id(),
                                namespaces.stream()
                                        .map(
                                                row ->
                                                        ResourceIds.encode(
                                                                ResourceIds.Type.NAMESPACE,
                                                                row.id()))
                                        .toList(),
                                now);
                boolean datasourceVisible =
                        !decisions.isEmpty() && decisions.values().stream().anyMatch(d -> d.meta());
                if (!datasourceVisible) {
                    Map<Long, AclDirectory.ResourceDecision> datasourceDecision =
                            decisions(
                                    userId,
                                    platform.id(),
                                    List.of(
                                            ResourceIds.encode(
                                                    ResourceIds.Type.DATASOURCE, datasource.id())),
                                    now);
                    datasourceVisible =
                            !datasourceDecision.isEmpty()
                                    && datasourceDecision.values().iterator().next().meta();
                }
                if (!datasourceVisible) {
                    throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
                }
                boolean discover = discover(platform);
                for (NamespaceRow namespace : namespaces) {
                    AclDirectory.ResourceDecision decision = decisions.get(namespace.id());
                    if (namespace.id() > after
                            && decision != null
                            && (discover ? decision.meta() : decision.read())) {
                        items.add(
                                new NamespaceItem(
                                        "NAMESPACE",
                                        namespace.id(),
                                        namespace.catalog(),
                                        blankToNull(namespace.schema()),
                                        displayName(namespace)));
                    }
                }
                versions.put(
                        "platform:" + platform.id(),
                        platform.scopeVersion() + "/" + platform.securityVersion());
                next = paginate(items, limit, item -> ((NamespaceItem) item).id());
            }
            default -> throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        return new AccessContext(List.copyOf(items), next, now, Map.copyOf(versions));
    }

    private String paginate(List<Object> items, int limit, IdGetter getter) {
        if (items.size() <= limit) {
            return null;
        }
        long lastReturned = getter.id(items.get(limit - 1));
        items.subList(limit, items.size()).clear();
        return Long.toString(lastReturned);
    }

    private interface IdGetter {
        long id(Object item);
    }

    // ------------------------------------------------------------------ dictionary

    public ObjectPage dictionary(
            long userId,
            long platformId,
            long datasourceId,
            String databaseName,
            String schemaName,
            String keyword,
            String cursor,
            Integer pageSize) {
        int limit = pageSize(pageSize);
        long after = cursorId(cursor);
        PlatformRow platform = requirePlatform(platformId);
        DatasourceRow datasource = requireDatasource(datasourceId);
        boolean discover = discover(platform);
        requireDatasourceVisible(userId, platform.id(), discover, datasource, Instant.now());
        List<ObjectSummary> items = new ArrayList<>();
        long scan = after;
        boolean exhausted = false;
        while (items.size() <= limit && !exhausted) {
            List<ObjectRow> rows =
                    objectRows(datasource.id(), scan, databaseName, schemaName, keyword, 100);
            if (rows.isEmpty()) {
                break;
            }
            scan = rows.get(rows.size() - 1).id();
            if (rows.size() < 100) {
                exhausted = true;
            }
            Map<Long, AclDirectory.ResourceDecision> decisions =
                    decisions(
                            userId,
                            platform.id(),
                            rows.stream()
                                    .map(
                                            row ->
                                                    ResourceIds.encode(
                                                            ResourceIds.Type.OBJECT, row.id()))
                                    .toList(),
                            Instant.now());
            for (ObjectRow row : rows) {
                AclDirectory.ResourceDecision decision = decisions.get(row.id());
                if (decision == null || !decision.meta() || (!discover && !decision.read())) {
                    continue;
                }
                items.add(
                        new ObjectSummary(
                                row.id(),
                                row.catalog(),
                                blankToNull(row.schema()),
                                row.objectName(),
                                row.kind(),
                                row.tableComment(),
                                decision.read(),
                                true));
            }
        }
        String next = null;
        if (items.size() > limit) {
            next = Long.toString(items.get(limit - 1).objectId());
            items.subList(limit, items.size()).clear();
        }
        return new ObjectPage(List.copyOf(items), next);
    }

    public ObjectDetail dictionaryDetail(long userId, long platformId, long objectId) {
        PlatformRow platform = requirePlatform(platformId);
        if (objectId <= 0) {
            throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        ObjectRow row = objectDetailRow(objectId);
        if (row == null) {
            throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        Map<Long, AclDirectory.ResourceDecision> decisions =
                decisions(
                        userId,
                        platform.id(),
                        List.of(ResourceIds.encode(ResourceIds.Type.OBJECT, objectId)),
                        Instant.now());
        AclDirectory.ResourceDecision decision = decisions.get(objectId);
        if (decision == null || !decision.meta()) {
            throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        if (!decision.read()) {
            throw new DomainException(ErrorCode.TABLE_NOT_AUTHORIZED);
        }
        DatasourceRow datasource = requireDatasource(row.datasourceId());
        if (datasource.activeSnapshotId() == null) {
            throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        SnapshotRow snapshot = snapshot(datasource.activeSnapshotId(), objectId);
        if (snapshot == null) {
            throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        DictionaryRow tableDictionary = tableDictionary(objectId);
        List<ColumnView> columns = columns(datasource.activeSnapshotId(), objectId);
        int foreignKeys = foreignKeyCount(datasource.activeSnapshotId(), objectId);
        return new ObjectDetail(
                objectId,
                row.catalog(),
                blankToNull(row.schema()),
                row.objectName(),
                row.kind(),
                snapshot.tableComment(),
                tableDictionary == null ? null : tableDictionary.title(),
                tableDictionary == null ? null : tableDictionary.description(),
                snapshot.resourceVersion(),
                row.generation(),
                true,
                true,
                columns,
                List.of(),
                foreignKeys > 0);
    }

    private record SnapshotRow(long resourceVersion, String tableComment) {}

    private record DictionaryRow(String title, String description) {}

    // ------------------------------------------------------------------ history

    public AuditQueryService.AuditPage history(
            long userId,
            Instant from,
            Instant to,
            String tool,
            String result,
            String verdict,
            String cursor,
            Integer pageSize) {
        return audits.history(userId, from, to, tool, result, verdict, cursor, pageSize);
    }

    public AuditQueryService.AuditDetail historyDetail(long userId, String requestId) {
        return audits.historyDetail(userId, requestId);
    }

    // ------------------------------------------------------------------ mcp config

    public McpConfig mcpConfig(long userId, long platformId, String clientType) {
        String type = clientType == null ? "" : clientType.trim().toUpperCase(Locale.ROOT);
        if (!CLIENT_TYPES.contains(type)) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        PlatformRow platform = requirePlatform(platformId);
        if (visibleDatasources(
                        userId,
                        platform.id(),
                        discover(platform),
                        activeDatasources(),
                        namespaces(),
                        Instant.now())
                .isEmpty()) {
            throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        Map<String, Object> server = new LinkedHashMap<>();
        server.put(
                "type",
                "VSCODE".equals(type) || "CLAUDE_CODE".equals(type) ? "http" : "streamable-http");
        if (mcpUrl != null) {
            server.put("url", mcpUrl);
        }
        server.put("headers", Map.of("Authorization", "Bearer <PAT_OR_OAUTH_ACCESS_TOKEN>"));
        String container = "VSCODE".equals(type) ? "servers" : "mcpServers";
        String template = JSON.writeValueAsString(Map.of(container, Map.of("ptmcp", server)));
        return new McpConfig(
                "streamable-http", mcpUrl, List.of("oauth", "pat"), template, "UNVERIFIED", null);
    }

    // ------------------------------------------------------------------ authorizations

    public AuthorizationPage authorizations(long userId, String cursor, Integer pageSize) {
        int limit = pageSize(pageSize);
        AuthorizationCursor after = decodeAuthorizationCursor(cursor);
        StringBuilder sql =
                new StringBuilder(
                        "SELECT f.id, c.client_name, f.platform_id, f.create_time,"
                                + " f.last_rotated_at, f.status, f.absolute_expires_at"
                                + " FROM oauth_refresh_family f LEFT JOIN oauth2_registered_client c"
                                + " ON c.id = f.registered_client_id WHERE f.user_id = ?");
        List<Object> parameters = new ArrayList<>();
        parameters.add(userId);
        if (after != null) {
            sql.append(" AND (f.create_time < ? OR (f.create_time = ? AND f.id < ?))");
            parameters.add(utc(after.issuedAt()));
            parameters.add(utc(after.issuedAt()));
            parameters.add(after.id());
        }
        sql.append(" ORDER BY f.create_time DESC, f.id DESC LIMIT ?");
        parameters.add(limit + 1);
        List<AuthorizationView> items = new ArrayList<>();
        String next = null;
        try (Connection connection = primary.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bind(statement, parameters);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    items.add(
                            new AuthorizationView(
                                    result.getString(1),
                                    result.getString(2),
                                    nullableLong(result, 3),
                                    toInstant(result.getObject(4, LocalDateTime.class)),
                                    toInstant(result.getObject(5, LocalDateTime.class)),
                                    result.getString(6),
                                    toInstant(result.getObject(7, LocalDateTime.class))));
                }
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        if (items.size() > limit) {
            AuthorizationView last = items.get(limit - 1);
            next = encodeAuthorizationCursor(last.issuedAt(), last.id());
            items.subList(limit, items.size()).clear();
        }
        return new AuthorizationPage(List.copyOf(items), next);
    }

    public AuthorizationRevoked revokeAuthorization(long userId, String familyId) {
        UUID parsed;
        try {
            parsed = UUID.fromString(familyId);
        } catch (RuntimeException failure) {
            throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        Instant now = Instant.now();
        try (Connection connection = primary.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String status;
                try (PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT status FROM oauth_refresh_family"
                                        + " WHERE id = ? AND user_id = ?")) {
                    statement.setString(1, parsed.toString());
                    statement.setLong(2, userId);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) {
                            throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
                        }
                        status = result.getString(1);
                    }
                }
                try (PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE oauth_refresh_family SET status = 'REVOKED',"
                                        + " revoked_at = COALESCE(revoked_at, ?),"
                                        + " revoke_reason = COALESCE(revoke_reason, ?),"
                                        + " version = version + 1, update_time = ?"
                                        + " WHERE id = ? AND user_id = ? AND status = 'ACTIVE'")) {
                    statement.setObject(1, utc(now));
                    statement.setString(2, "USER_REVOKED");
                    statement.setObject(3, utc(now));
                    statement.setString(4, parsed.toString());
                    statement.setLong(5, userId);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE oauth2_authorization SET status = 'REVOKED',"
                                        + " version = version + 1, update_time = ?"
                                        + " WHERE family_id = ? AND user_id = ?"
                                        + " AND status = 'ACTIVE'")) {
                    statement.setObject(1, utc(now));
                    statement.setString(2, parsed.toString());
                    statement.setLong(3, userId);
                    statement.executeUpdate();
                }
                connection.commit();
                return new AuthorizationRevoked(
                        parsed.toString(), "ACTIVE".equals(status) ? "REVOKED" : status, true);
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    // Keep the original failure.
                }
                throw failure;
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private record AuthorizationCursor(Instant issuedAt, String id) {}

    // ------------------------------------------------------------------ queries

    private PlatformRow requirePlatform(Long platformId) {
        if (platformId == null || platformId <= 0) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        try (Connection connection = primary.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT id, name, tier, catalog_mode, scope_version,"
                                        + " security_version FROM platform_registry"
                                        + " WHERE id = ? AND status = 'ACTIVE'")) {
            statement.setLong(1, platformId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
                }
                return new PlatformRow(
                        result.getLong(1),
                        result.getString(2),
                        result.getInt(3),
                        result.getString(4),
                        result.getString(5),
                        result.getString(6));
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private DatasourceRow requireDatasource(Long datasourceId) {
        if (datasourceId == null || datasourceId <= 0) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        try (Connection connection = primary.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT id, name, engine, active_snapshot_id, resource_version"
                                        + " FROM datasource WHERE id = ? AND status = 'ACTIVE'")) {
            statement.setLong(1, datasourceId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
                }
                long snapshot = result.getLong(4);
                return new DatasourceRow(
                        result.getLong(1),
                        result.getString(2),
                        result.getString(3),
                        result.wasNull() ? null : snapshot,
                        result.getLong(5));
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private List<PlatformRow> activePlatforms() {
        List<PlatformRow> platforms = new ArrayList<>();
        try (Connection connection = primary.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT id, name, tier, catalog_mode, scope_version,"
                                        + " security_version FROM platform_registry"
                                        + " WHERE status = 'ACTIVE' ORDER BY id");
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                platforms.add(
                        new PlatformRow(
                                result.getLong(1),
                                result.getString(2),
                                result.getInt(3),
                                result.getString(4),
                                result.getString(5),
                                result.getString(6)));
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        return platforms;
    }

    private List<DatasourceRow> activeDatasources() {
        List<DatasourceRow> datasources = new ArrayList<>();
        try (Connection connection = primary.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT id, name, engine, active_snapshot_id, resource_version"
                                        + " FROM datasource WHERE status = 'ACTIVE' ORDER BY id");
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                long snapshot = result.getLong(4);
                datasources.add(
                        new DatasourceRow(
                                result.getLong(1),
                                result.getString(2),
                                result.getString(3),
                                result.wasNull() ? null : snapshot,
                                result.getLong(5)));
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        return datasources;
    }

    private List<NamespaceRow> namespaces() {
        List<NamespaceRow> namespaces = new ArrayList<>();
        try (Connection connection = primary.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT id, datasource_id, catalog_raw, schema_raw"
                                        + " FROM resource_namespace WHERE status = 'ACTIVE'"
                                        + " ORDER BY id");
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                namespaces.add(namespace(result));
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        return namespaces;
    }

    private List<NamespaceRow> namespacesOf(long datasourceId) {
        List<NamespaceRow> namespaces = new ArrayList<>();
        try (Connection connection = primary.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT id, datasource_id, catalog_raw, schema_raw"
                                        + " FROM resource_namespace WHERE datasource_id = ?"
                                        + " AND status = 'ACTIVE' ORDER BY id")) {
            statement.setLong(1, datasourceId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    namespaces.add(namespace(result));
                }
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        return namespaces;
    }

    private Set<Long> visibleDatasources(
            long userId,
            long platformId,
            boolean discover,
            List<DatasourceRow> datasources,
            List<NamespaceRow> namespaces,
            Instant at) {
        Map<Long, AclDirectory.ResourceDecision> datasourceDecisions =
                decisions(
                        userId,
                        platformId,
                        datasources.stream()
                                .map(
                                        row ->
                                                ResourceIds.encode(
                                                        ResourceIds.Type.DATASOURCE, row.id()))
                                .toList(),
                        at);
        Map<Long, AclDirectory.ResourceDecision> namespaceDecisions =
                decisions(
                        userId,
                        platformId,
                        namespaces.stream()
                                .map(
                                        row ->
                                                ResourceIds.encode(
                                                        ResourceIds.Type.NAMESPACE, row.id()))
                                .toList(),
                        at);
        java.util.Set<Long> visible = new java.util.HashSet<>();
        for (DatasourceRow datasource : datasources) {
            AclDirectory.ResourceDecision decision = datasourceDecisions.get(datasource.id());
            if (decision != null && (discover ? decision.meta() : decision.read())) {
                visible.add(datasource.id());
            }
        }
        for (NamespaceRow namespace : namespaces) {
            AclDirectory.ResourceDecision decision = namespaceDecisions.get(namespace.id());
            if (decision != null && (discover ? decision.meta() : decision.read())) {
                visible.add(namespace.datasourceId());
            }
        }
        return Set.copyOf(visible);
    }

    private void requireDatasourceVisible(
            long userId, long platformId, boolean discover, DatasourceRow datasource, Instant at) {
        Map<Long, AclDirectory.ResourceDecision> datasourceDecision =
                decisions(
                        userId,
                        platformId,
                        List.of(ResourceIds.encode(ResourceIds.Type.DATASOURCE, datasource.id())),
                        at);
        AclDirectory.ResourceDecision decision = datasourceDecision.get(datasource.id());
        if (decision != null && (discover ? decision.meta() : decision.read())) {
            return;
        }
        Map<Long, AclDirectory.ResourceDecision> namespaceDecisions =
                decisions(
                        userId,
                        platformId,
                        namespacesOf(datasource.id()).stream()
                                .map(
                                        row ->
                                                ResourceIds.encode(
                                                        ResourceIds.Type.NAMESPACE, row.id()))
                                .toList(),
                        at);
        if (namespaceDecisions.values().stream()
                .anyMatch(item -> discover ? item.meta() : item.read())) {
            return;
        }
        throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
    }

    private static boolean discover(PlatformRow platform) {
        return "DISCOVER".equals(platform.catalogMode());
    }

    private Map<Long, AclDirectory.ResourceDecision> decisions(
            long userId, long platformId, List<Long> resourceIds, Instant at) {
        Map<Long, AclDirectory.ResourceDecision> decisions = new HashMap<>();
        if (resourceIds.isEmpty()) {
            return decisions;
        }
        for (int from = 0; from < resourceIds.size(); from += 100) {
            List<Long> chunk = resourceIds.subList(from, Math.min(from + 100, resourceIds.size()));
            for (AclDirectory.ResourceDecision decision :
                    directory.preview(userId, platformId, chunk, at)) {
                decisions.put(ResourceIds.tableIdOf(decision.resourceId()), decision);
            }
        }
        return decisions;
    }

    private List<ObjectRow> objectRows(
            long datasourceId,
            long after,
            String databaseName,
            String schemaName,
            String keyword,
            int limit) {
        StringBuilder sql =
                new StringBuilder(
                        "SELECT o.id, o.namespace_id, n.datasource_id, n.catalog_raw,"
                                + " n.schema_raw, o.object_raw, o.kind, so.table_comment"
                                + " FROM resource_object o"
                                + " JOIN resource_namespace n ON n.id = o.namespace_id"
                                + " JOIN datasource d ON d.id = n.datasource_id"
                                + " LEFT JOIN snapshot_object so"
                                + " ON so.snapshot_id = d.active_snapshot_id AND so.object_id = o.id"
                                + " WHERE n.datasource_id = ? AND n.status = 'ACTIVE'"
                                + " AND o.status = 'ACTIVE' AND o.id > ?");
        List<Object> parameters = new ArrayList<>();
        parameters.add(datasourceId);
        parameters.add(after);
        if (databaseName != null && !databaseName.isBlank()) {
            sql.append(" AND LOWER(n.catalog_raw) = LOWER(?)");
            parameters.add(databaseName.trim());
        }
        if (schemaName != null && !schemaName.isBlank()) {
            sql.append(" AND LOWER(n.schema_raw) = LOWER(?)");
            parameters.add(schemaName.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND LOWER(o.object_raw) LIKE ?");
            parameters.add("%" + escapeLike(keyword.trim().toLowerCase(Locale.ROOT)) + "%");
        }
        sql.append(" ORDER BY o.id LIMIT ?");
        parameters.add(limit);
        List<ObjectRow> rows = new ArrayList<>();
        try (Connection connection = primary.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bind(statement, parameters);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(
                            new ObjectRow(
                                    result.getLong(1),
                                    result.getLong(2),
                                    result.getLong(3),
                                    result.getString(4),
                                    result.getString(5),
                                    result.getString(6),
                                    result.getString(7),
                                    result.getString(8),
                                    0));
                }
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        return rows;
    }

    private ObjectRow objectDetailRow(long objectId) {
        try (Connection connection = primary.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT o.id, o.namespace_id, n.datasource_id, n.catalog_raw,"
                                        + " n.schema_raw, o.object_raw, o.kind,"
                                        + " so.table_comment, o.generation"
                                        + " FROM resource_object o"
                                        + " JOIN resource_namespace n ON n.id = o.namespace_id"
                                        + " JOIN datasource d ON d.id = n.datasource_id"
                                        + " LEFT JOIN snapshot_object so"
                                        + " ON so.snapshot_id = d.active_snapshot_id"
                                        + " AND so.object_id = o.id"
                                        + " WHERE o.id = ? AND o.status = 'ACTIVE'"
                                        + " AND n.status = 'ACTIVE' AND d.status = 'ACTIVE'")) {
            statement.setLong(1, objectId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new ObjectRow(
                        result.getLong(1),
                        result.getLong(2),
                        result.getLong(3),
                        result.getString(4),
                        result.getString(5),
                        result.getString(6),
                        result.getString(7),
                        result.getString(8),
                        result.getLong(9));
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private SnapshotRow snapshot(long snapshotId, long objectId) {
        try (Connection connection = primary.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT s.resource_version, so.table_comment"
                                        + " FROM schema_snapshot s JOIN snapshot_object so"
                                        + " ON so.snapshot_id = s.id"
                                        + " WHERE s.id = ? AND so.object_id = ?")) {
            statement.setLong(1, snapshotId);
            statement.setLong(2, objectId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? new SnapshotRow(result.getLong(1), result.getString(2))
                        : null;
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private DictionaryRow tableDictionary(long objectId) {
        try (Connection connection = primary.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT title, description FROM business_dictionary"
                                        + " WHERE object_id = ? AND column_norm = ''")) {
            statement.setLong(1, objectId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? new DictionaryRow(result.getString(1), result.getString(2))
                        : null;
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private List<ColumnView> columns(long snapshotId, long objectId) {
        List<ColumnView> columns = new ArrayList<>();
        try (Connection connection = primary.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT c.ordinal, c.column_raw, c.data_type, c.jdbc_type,"
                                        + " c.nullable, c.precision_value, c.scale_value,"
                                        + " c.comment, c.is_primary_key, bd.title, bd.description"
                                        + " FROM resource_column c"
                                        + " LEFT JOIN business_dictionary bd"
                                        + " ON bd.object_id = c.object_id"
                                        + " AND bd.column_norm = c.column_norm"
                                        + " WHERE c.snapshot_id = ? AND c.object_id = ?"
                                        + " ORDER BY c.ordinal")) {
            statement.setLong(1, snapshotId);
            statement.setLong(2, objectId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    columns.add(
                            new ColumnView(
                                    result.getInt(1),
                                    result.getString(2),
                                    result.getString(3),
                                    result.getInt(4),
                                    result.getInt(5) == 1,
                                    nullableInt(result, 6),
                                    nullableInt(result, 7),
                                    result.getString(8),
                                    result.getInt(9) == 1,
                                    result.getString(10),
                                    result.getString(11)));
                }
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        return columns;
    }

    private static NamespaceRow namespace(ResultSet result) throws SQLException {
        return new NamespaceRow(
                result.getLong(1), result.getLong(2), result.getString(3), result.getString(4));
    }

    private static String displayName(NamespaceRow namespace) {
        return namespace.schema() == null || namespace.schema().isBlank()
                ? namespace.catalog()
                : namespace.catalog() + "." + namespace.schema();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String optionalFilter(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        return trimmed;
    }

    private static String optionalEnum(String value, Set<String> allowed) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        return normalized;
    }

    private static int pageSize(Integer pageSize) {
        if (pageSize == null) {
            return DEFAULT_PAGE;
        }
        if (pageSize < 1 || pageSize > MAX_PAGE) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        return pageSize;
    }

    private static long cursorId(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
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

    private static String encodeAuthorizationCursor(Instant issuedAt, String id) {
        String raw = issuedAt.toString() + "|" + id;
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static AuthorizationCursor decodeAuthorizationCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String raw =
                    new String(
                            Base64.getUrlDecoder().decode(cursor.trim()), StandardCharsets.UTF_8);
            int separator = raw.lastIndexOf('|');
            if (separator <= 0) {
                throw new IllegalArgumentException("missing separator");
            }
            return new AuthorizationCursor(
                    Instant.parse(raw.substring(0, separator)), raw.substring(separator + 1));
        } catch (IllegalArgumentException | DateTimeParseException failure) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
    }

    private static void bind(PreparedStatement statement, List<Object> parameters)
            throws SQLException {
        for (int index = 0; index < parameters.size(); index++) {
            statement.setObject(index + 1, parameters.get(index));
        }
    }

    private int foreignKeyCount(long snapshotId, long objectId) {
        try (Connection connection = primary.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT COUNT(*) FROM resource_column WHERE snapshot_id = ?"
                                        + " AND object_id = ? AND foreign_key IS NOT NULL")) {
            statement.setLong(1, snapshotId);
            statement.setLong(2, objectId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private static Long nullableLong(ResultSet result, int column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static Integer nullableInt(ResultSet result, int column) throws SQLException {
        int value = result.getInt(column);
        return result.wasNull() ? null : value;
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, UTC);
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(UTC);
    }
}
