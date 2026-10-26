package local.ptmcp.gateway.mcp;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.common.resource.DatabaseEngine;
import local.ptmcp.common.resource.ResourceId;
import local.ptmcp.common.resource.ResourceNamespace;
import local.ptmcp.common.resource.ResourceRef;
import local.ptmcp.datasource.ApprovedQueryAdapter;
import local.ptmcp.datasource.DataSourcePoolRegistry;
import local.ptmcp.datasource.PoolHandle;
import local.ptmcp.datasource.PoolKey;
import local.ptmcp.datasource.PoolLimits;
import local.ptmcp.datasource.ReadOnlyCancellation;
import local.ptmcp.datasource.ReadOnlyResult;
import local.ptmcp.datasource.connection.CredentialCipher;
import local.ptmcp.datasource.postgres.PgApprovedQueryAdapter;
import local.ptmcp.datasource.postgres.PgReadOnlyResult;
import local.ptmcp.guard.approved.ApprovedQuery;
import local.ptmcp.guard.approved.GuardContext;
import local.ptmcp.guard.approved.GuardDependencies;
import local.ptmcp.guard.approved.GuardPipeline;
import local.ptmcp.guard.approved.GuardPolicy;
import local.ptmcp.guard.budget.BoundedParser;
import local.ptmcp.guard.scope.ColumnDef;
import local.ptmcp.guard.scope.RelationColumns;
import local.ptmcp.guard.scope.ScopeTableBinder;
import local.ptmcp.persistence.audit.AuditRepository;
import local.ptmcp.persistence.audit.AuditStart;
import local.ptmcp.persistence.audit.AuditTerminal;
import local.ptmcp.persistence.audit.wal.TerminalWal;
import local.ptmcp.persistence.org.AuthorizationReader;
import local.ptmcp.persistence.schema.PublishedSnapshotReader;
import local.ptmcp.persistence.security.QuotaLimiter;
import local.ptmcp.persistence.security.SecuritySnapshot;
import local.ptmcp.persistence.security.SecurityStateReader;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The MCP tool boundary: JSON-RPC envelope handling, the three closed tools, and the ordered
 * admission→guard→execute→terminal pipeline. Every authenticated tools/call validates its arguments
 * first, persists its START on the primary metadata database before any guard work, and only
 * reports success after a durable terminal (primary or fsynced WAL). Tool-level failures become
 * isError results; audit unavailability surfaces as HTTP 503. No SQL text, credential or driver
 * message ever reaches a response or an audit payload.
 */
final class McpToolService {
    static final int RESPONSE_BUDGET_BYTES = 1_048_576;
    private static final int SQL_BYTE_BUDGET = 64 * 1024;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_ROWS_LIMIT = 500;
    private static final String PROTOCOL_VERSION = "2025-06-18";
    private static final Set<String> TOOLS =
            Set.of("list_databases_and_tables", "get_table_schema", "execute_readonly_sql");

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final SecurityStateReader security;
    private final McpAuditWriter audit;
    private final PublishedSnapshotReader snapshots;
    private final AuthorizationReader authorization;
    private final ToolPermissionService permissions;
    private final CredentialCipher cipher;
    private final DataSourcePoolRegistry pools;
    private final ApprovedQueryAdapter adapter;
    private final PgApprovedQueryAdapter pgAdapter;
    private final QuotaLimiter quotas;
    private final BoundedParser parser;
    private final McpAuthentication authentication;

    McpToolService(
            SecurityStateReader security,
            AuditRepository auditRepository,
            TerminalWal terminalWal,
            PublishedSnapshotReader snapshots,
            AuthorizationReader authorization,
            CredentialCipher cipher,
            DataSourcePoolRegistry pools,
            ApprovedQueryAdapter adapter,
            PgApprovedQueryAdapter pgAdapter,
            QuotaLimiter quotas,
            BoundedParser parser,
            McpAuthentication authentication) {
        this.security = Objects.requireNonNull(security, "security");
        this.audit = new McpAuditWriter(auditRepository, terminalWal);
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.permissions = new ToolPermissionService(authorization, true);
        this.cipher = Objects.requireNonNull(cipher, "cipher");
        this.pools = Objects.requireNonNull(pools, "pools");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.pgAdapter = Objects.requireNonNull(pgAdapter, "pgAdapter");
        this.quotas = Objects.requireNonNull(quotas, "quotas");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.authentication = Objects.requireNonNull(authentication, "authentication");
    }

    /** One handled JSON-RPC HTTP exchange; {@code body} is the exact response payload. */
    record Outcome(int status, String body) {
        static Outcome json(String body) {
            return new Outcome(200, body);
        }

        static Outcome accepted() {
            return new Outcome(202, "");
        }
    }

    private enum Stage {
        PRE_GUARD,
        GUARD,
        EXECUTION,
        ENCODE
    }

    Outcome handle(McpIdentity identity, String body, String boundaryRequestId) {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (RuntimeException malformed) {
            return Outcome.json(rpcError(null, -32700, "Parse error"));
        }
        if (!root.isObject() || !"2.0".equals(root.path("jsonrpc").asText(null))) {
            return Outcome.json(rpcError(null, -32600, "Invalid Request"));
        }
        String method = root.path("method").asText(null);
        if (method == null || method.isBlank()) {
            return Outcome.json(rpcError(null, -32600, "Invalid Request"));
        }
        JsonNode id = root.path("id");
        if (id.isMissingNode() || id.isNull()) {
            // Notifications carry no response; this stateless boundary never queues work on them.
            return Outcome.accepted();
        }
        if (!id.isNumber() && !id.isTextual()) {
            return Outcome.json(rpcError(null, -32600, "Invalid Request"));
        }
        return switch (method) {
            case "initialize" -> Outcome.json(envelope(id, initializeResult()));
            case "ping" -> Outcome.json(envelope(id, Map.of()));
            case "tools/list" -> Outcome.json(envelope(id, Map.of("tools", toolDescriptors())));
            case "tools/call" -> callTool(identity, root.path("params"), id, boundaryRequestId);
            default -> Outcome.json(rpcError(id, -32601, "Method not found"));
        };
    }

    private Outcome callTool(
            McpIdentity identity, JsonNode params, JsonNode id, String boundaryRequestId) {
        if (!params.isObject()) {
            return Outcome.json(rpcError(id, -32602, "Invalid params"));
        }
        String name = params.path("name").asText(null);
        if (name == null || !TOOLS.contains(name)) {
            return Outcome.json(rpcError(id, -32602, "Invalid params"));
        }
        JsonNode arguments = params.path("arguments");
        if (arguments.isMissingNode()) {
            arguments = JSON.createObjectNode();
        }
        if (!arguments.isObject()) {
            return Outcome.json(rpcError(id, -32602, "Invalid params"));
        }
        UUID requestId = parseRequestId(boundaryRequestId);
        try {
            return switch (name) {
                case "execute_readonly_sql" ->
                        Outcome.json(executeReadonlySql(identity, requestId, arguments, id));
                case "list_databases_and_tables" ->
                        Outcome.json(listTables(identity, requestId, arguments, id));
                default -> Outcome.json(getTableSchema(identity, requestId, arguments, id));
            };
        } catch (IllegalArgumentException invalid) {
            return Outcome.json(rpcError(id, -32602, "Invalid params"));
        }
    }

    // ---------------------------------------------------------------- execute_readonly_sql

    private String executeReadonlySql(
            McpIdentity identity, UUID requestId, JsonNode args, JsonNode id) {
        long datasourceId = ResourceId.parse(requiredText(args, "datasource_id")).value();
        String databaseName = requiredText(args, "database_name");
        String schemaName = optionalText(args, "schema_name");
        String sql = requiredText(args, "sql_query");
        int maxRows = optionalInt(args, "max_rows", MAX_ROWS_LIMIT);
        if (maxRows < 1 || maxRows > MAX_ROWS_LIMIT) {
            throw new IllegalArgumentException("max_rows");
        }
        byte[] sqlBytes = sql.getBytes(StandardCharsets.UTF_8);
        if (sqlBytes.length > SQL_BYTE_BUDGET) {
            throw new IllegalArgumentException("sql_query");
        }
        Instant startedAt = Instant.now();
        long startedNanos = System.nanoTime();
        Integer queueMs = null;
        audit.start(
                new AuditStart(
                        requestId,
                        identity.userId(),
                        identity.platformId(),
                        "execute_readonly_sql",
                        sha256(sqlBytes),
                        sqlBytes.length,
                        startedAt));
        Stage stage = Stage.PRE_GUARD;
        ApprovedQuery approved = null;
        try {
            requireScope(identity, "mcp:query");
            PublishedSnapshotReader.Published published = loadUsable(datasourceId);
            DatabaseEngine engine = requireDialect(published, schemaName);
            PublishedSnapshotReader.Namespace namespace =
                    resolveNamespace(
                            published,
                            databaseName,
                            engine == DatabaseEngine.MYSQL ? "" : schemaName);
            SecuritySnapshot snapshot = freshSnapshot(identity);

            stage = Stage.GUARD;
            ToolPermissionService.Session session =
                    permissions.open(
                            identity.userId(), identity.platformId(), datasourceId, published);
            GuardContext context =
                    new GuardContext(
                            identity.userId(),
                            identity.platformId(),
                            new ResourceId(datasourceId),
                            published.configVersion(),
                            engine == DatabaseEngine.POSTGRESQL
                                    ? published.databaseName()
                                    : namespace.catalogNorm(),
                            namespace.schemaNorm(),
                            new ResourceId(published.snapshotId()),
                            published.resourceVersion(),
                            snapshot.policyEpoch(),
                            identity.authVersion(),
                            snapshot.checkedAt());
            GuardDependencies deps =
                    new GuardDependencies(
                            parser,
                            relationCatalog(published, namespace, engine),
                            relationColumns(published),
                            (ctx, resource) -> session.decide(resource.objectId().value()).read(),
                            Clock.systemUTC());
            GuardPolicy policy =
                    engine == DatabaseEngine.MYSQL
                            ? GuardPolicy.mysql(false, false, maxRows)
                            : GuardPolicy.postgresql(true, maxRows);
            approved = GuardPipeline.guard(sql, context, policy, deps);

            stage = Stage.EXECUTION;
            long queueStarted = System.nanoTime();
            QuotaLimiter.ExecutionPermit permit;
            try {
                permit = awaitExecution(identity.userId(), datasourceId);
            } finally {
                queueMs = elapsedMillis(queueStarted);
            }
            ToolResultEncoder.Encoded encoded;
            try {
                PoolHandle handle = activatePool(published, engine);
                if (engine == DatabaseEngine.MYSQL) {
                    ReadOnlyResult result =
                            adapter.execute(
                                    approved,
                                    datasource -> {
                                        authentication.revalidate(identity);
                                        audit.confirmStart(requestId);
                                    },
                                    new ReadOnlyCancellation());
                    encoded = ToolResultEncoder.encode(result, result.limitReached());
                } else {
                    PgReadOnlyResult result =
                            pgAdapter.execute(
                                    approved,
                                    handle.dataSource(),
                                    handle.generation(),
                                    datasource -> {
                                        authentication.revalidate(identity);
                                        audit.confirmStart(requestId);
                                    },
                                    new ReadOnlyCancellation());
                    encoded = ToolResultEncoder.encode(result);
                }
            } finally {
                quotas.release(permit);
            }

            stage = Stage.ENCODE;
            int durationMs = elapsedMillis(startedNanos);
            Budgeted success =
                    fitRows(
                            id,
                            requestId,
                            Long.toString(datasourceId),
                            databaseName(published, namespace),
                            namespace.schemaRaw(),
                            encoded,
                            durationMs);
            terminal(
                    requestId,
                    identity,
                    datasourceId,
                    startedAt,
                    AuditTerminal.Result.SUCCESS,
                    AuditTerminal.Verdict.ALLOW,
                    null,
                    durationMs,
                    queueMs,
                    success.rowsReturned(),
                    success.truncated(),
                    approved.sqlHash(),
                    "execute_readonly_sql");
            return envelope(id, callToolResult(false, success.structured()));
        } catch (DomainException failure) {
            if (failure.errorCode() == ErrorCode.AUDIT_UNAVAILABLE) {
                throw failure;
            }
            int durationMs = elapsedMillis(startedNanos);
            terminal(
                    requestId,
                    identity,
                    datasourceId,
                    startedAt,
                    AuditTerminal.Result.ERROR,
                    verdictFor(stage, failure.errorCode()),
                    failure.errorCode().name(),
                    durationMs,
                    queueMs,
                    null,
                    false,
                    approved == null ? null : approved.sqlHash(),
                    "execute_readonly_sql");
            return envelope(
                    id, callToolResult(true, errorContent(requestId, failure.errorCode(), stage)));
        }
    }

    // ------------------------------------------------------- list_databases_and_tables

    private String listTables(McpIdentity identity, UUID requestId, JsonNode args, JsonNode id) {
        long datasourceId = ResourceId.parse(requiredText(args, "datasource_id")).value();
        String databaseName = optionalText(args, "database_name");
        String schemaName = optionalText(args, "schema_name");
        String keyword = optionalText(args, "keyword");
        String cursor = optionalText(args, "cursor");
        int pageSize = optionalInt(args, "page_size", DEFAULT_PAGE_SIZE);
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("page_size");
        }
        CursorPosition position = decodeCursor(cursor);
        Instant startedAt = Instant.now();
        long startedNanos = System.nanoTime();
        audit.start(
                new AuditStart(
                        requestId,
                        identity.userId(),
                        identity.platformId(),
                        "list_databases_and_tables",
                        null,
                        null,
                        startedAt));
        try {
            requireScope(identity, "mcp:catalog");
            PublishedSnapshotReader.Published published = loadUsable(datasourceId);
            requireDialect(published, schemaName);
            AuthorizationReader.PlatformInfo platform =
                    authorization.loadPlatform(identity.platformId());
            ToolPermissionService.Session session =
                    permissions.open(
                            identity.userId(), identity.platformId(), datasourceId, published);

            List<Map<String, Object>> items = new ArrayList<>();
            long lastNamespaceId = 0;
            long lastObjectId = 0;
            boolean hasMore = false;
            int emitted = 0;
            boolean postgres = "POSTGRESQL".equals(published.engine());
            for (PublishedSnapshotReader.Namespace namespace : published.namespaces()) {
                if (databaseName != null
                        && !(postgres
                                ? databaseName.equals(published.databaseName())
                                : namespace.catalogNorm().equals(databaseName))) {
                    continue;
                }
                if (schemaName != null && !namespace.schemaNorm().equals(schemaName)) {
                    continue;
                }
                for (PublishedSnapshotReader.TableObject object : namespace.objects()) {
                    if (position != null && !position.before(namespace.id(), object.id())) {
                        continue;
                    }
                    if (keyword != null && !object.objectRaw().contains(keyword)) {
                        continue;
                    }
                    boolean visible =
                            "DISCOVER".equals(platform.catalogMode())
                                    ? session.platformVisible(object.id())
                                            && !session.explicitMetaDeny(object.id())
                                    : session.decide(object.id()).meta()
                                            || session.decide(object.id()).read();
                    if (!visible) {
                        continue;
                    }
                    if (emitted == pageSize) {
                        hasMore = true;
                        break;
                    }
                    items.add(
                            catalogItem(
                                    published,
                                    namespace,
                                    object,
                                    session.decide(object.id()).read()));
                    lastNamespaceId = namespace.id();
                    lastObjectId = object.id();
                    emitted++;
                }
                if (hasMore) {
                    break;
                }
            }
            String nextCursor = hasMore ? encodeCursor(lastNamespaceId, lastObjectId) : null;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("datasourceId", Long.toString(datasourceId));
            data.put("snapshotVersion", Long.toString(published.snapshotId()));
            data.put("catalogMode", platform.catalogMode());
            data.put("items", items);
            data.put("nextCursor", nextCursor);
            Map<String, Object> structured = successContent(requestId, data);
            String body = envelope(id, callToolResult(false, structured));
            requireWithinBudget(body);
            terminal(
                    requestId,
                    identity,
                    datasourceId,
                    startedAt,
                    AuditTerminal.Result.SUCCESS,
                    AuditTerminal.Verdict.ALLOW,
                    null,
                    elapsedMillis(startedNanos),
                    null,
                    items.size(),
                    false,
                    null,
                    "list_databases_and_tables");
            return body;
        } catch (DomainException failure) {
            if (failure.errorCode() == ErrorCode.AUDIT_UNAVAILABLE) {
                throw failure;
            }
            terminal(
                    requestId,
                    identity,
                    datasourceId,
                    startedAt,
                    AuditTerminal.Result.ERROR,
                    failure.errorCode() == ErrorCode.TABLE_NOT_AUTHORIZED
                            ? AuditTerminal.Verdict.DENY
                            : AuditTerminal.Verdict.ERROR,
                    failure.errorCode().name(),
                    elapsedMillis(startedNanos),
                    null,
                    null,
                    false,
                    null,
                    "list_databases_and_tables");
            return envelope(
                    id,
                    callToolResult(
                            true, errorContent(requestId, failure.errorCode(), Stage.PRE_GUARD)));
        }
    }

    private static Map<String, Object> catalogItem(
            PublishedSnapshotReader.Published published,
            PublishedSnapshotReader.Namespace namespace,
            PublishedSnapshotReader.TableObject object,
            boolean authorized) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("objectId", Long.toString(object.id()));
        item.put("databaseName", databaseName(published, namespace));
        item.put(
                "schemaName",
                namespace.schemaRaw() == null || namespace.schemaRaw().isEmpty()
                        ? null
                        : namespace.schemaRaw());
        item.put("tableName", object.objectRaw());
        item.put("objectType", object.kind());
        item.put("tableComment", object.tableComment());
        item.put("authorized", authorized);
        item.put("metadataVisible", true);
        return item;
    }

    // ---------------------------------------------------------------- get_table_schema

    private String getTableSchema(
            McpIdentity identity, UUID requestId, JsonNode args, JsonNode id) {
        long datasourceId = ResourceId.parse(requiredText(args, "datasource_id")).value();
        String databaseName = requiredText(args, "database_name");
        String schemaName = optionalText(args, "schema_name");
        List<String> tableNames = textArray(args, "table_names", 20);
        Instant startedAt = Instant.now();
        long startedNanos = System.nanoTime();
        audit.start(
                new AuditStart(
                        requestId,
                        identity.userId(),
                        identity.platformId(),
                        "get_table_schema",
                        null,
                        null,
                        startedAt));
        try {
            requireScope(identity, "mcp:schema");
            PublishedSnapshotReader.Published published = loadUsable(datasourceId);
            DatabaseEngine engine = requireDialect(published, schemaName);
            PublishedSnapshotReader.Namespace namespace =
                    resolveNamespace(
                            published,
                            databaseName,
                            engine == DatabaseEngine.MYSQL ? "" : schemaName);
            ToolPermissionService.Session session =
                    permissions.open(
                            identity.userId(), identity.platformId(), datasourceId, published);
            List<Map<String, Object>> tables = new ArrayList<>(tableNames.size());
            for (String tableName : tableNames) {
                PublishedSnapshotReader.TableObject object = findObject(namespace, tableName);
                if (!session.decide(object.id()).read()) {
                    throw new DomainException(ErrorCode.TABLE_NOT_AUTHORIZED);
                }
                tables.add(tableDescriptor(namespace, object, session));
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("datasourceId", Long.toString(datasourceId));
            data.put("databaseName", databaseName(published, namespace));
            data.put(
                    "schemaName",
                    namespace.schemaRaw() == null || namespace.schemaRaw().isEmpty()
                            ? null
                            : namespace.schemaRaw());
            data.put("snapshotVersion", Long.toString(published.snapshotId()));
            data.put("tables", tables);
            Map<String, Object> structured = successContent(requestId, data);
            String body = envelope(id, callToolResult(false, structured));
            requireWithinBudget(body);
            terminal(
                    requestId,
                    identity,
                    datasourceId,
                    startedAt,
                    AuditTerminal.Result.SUCCESS,
                    AuditTerminal.Verdict.ALLOW,
                    null,
                    elapsedMillis(startedNanos),
                    null,
                    tables.size(),
                    false,
                    null,
                    "get_table_schema");
            return body;
        } catch (DomainException failure) {
            if (failure.errorCode() == ErrorCode.AUDIT_UNAVAILABLE) {
                throw failure;
            }
            terminal(
                    requestId,
                    identity,
                    datasourceId,
                    startedAt,
                    AuditTerminal.Result.ERROR,
                    failure.errorCode() == ErrorCode.TABLE_NOT_AUTHORIZED
                            ? AuditTerminal.Verdict.DENY
                            : AuditTerminal.Verdict.ERROR,
                    failure.errorCode().name(),
                    elapsedMillis(startedNanos),
                    null,
                    null,
                    false,
                    null,
                    "get_table_schema");
            return envelope(
                    id,
                    callToolResult(
                            true, errorContent(requestId, failure.errorCode(), Stage.PRE_GUARD)));
        }
    }

    private Map<String, Object> tableDescriptor(
            PublishedSnapshotReader.Namespace namespace,
            PublishedSnapshotReader.TableObject object,
            ToolPermissionService.Session session) {
        List<String> primaryKey = new ArrayList<>();
        List<Map<String, Object>> columns = new ArrayList<>(object.columns().size());
        for (PublishedSnapshotReader.Column column : object.columns()) {
            if (column.primaryKey()) {
                primaryKey.add(column.columnRaw());
            }
            Map<String, Object> descriptor = new LinkedHashMap<>();
            descriptor.put("ordinal", column.ordinal());
            descriptor.put("name", column.columnRaw());
            descriptor.put("databaseType", column.dataType());
            descriptor.put("jdbcType", column.jdbcType());
            descriptor.put("nullable", column.nullable());
            descriptor.put("precision", column.precision());
            descriptor.put("scale", column.scale());
            descriptor.put("comment", column.comment());
            columns.add(descriptor);
        }
        boolean restricted = false;
        List<Map<String, Object>> foreignKeys = new ArrayList<>();
        Map<String, ForeignKeyGroup> groups = new LinkedHashMap<>();
        for (PublishedSnapshotReader.Column column : object.columns()) {
            if (column.foreignKeyJson() == null) {
                continue;
            }
            ForeignKeyReference reference = parseForeignKey(column.foreignKeyJson());
            if (reference == null) {
                continue;
            }
            groups.computeIfAbsent(reference.referencedTable(), key -> new ForeignKeyGroup())
                    .add(reference, column.columnRaw());
        }
        for (Map.Entry<String, ForeignKeyGroup> entry : groups.entrySet()) {
            Optional<PublishedSnapshotReader.TableObject> referenced =
                    namespace.objects().stream()
                            .filter(candidate -> candidate.objectNorm().equals(entry.getKey()))
                            .findFirst();
            if (referenced.isEmpty() || !session.decide(referenced.get().id()).read()) {
                restricted = true;
                continue;
            }
            ForeignKeyGroup group = entry.getValue();
            Map<String, Object> foreignKey = new LinkedHashMap<>();
            foreignKey.put("name", null);
            foreignKey.put("columns", group.columns());
            foreignKey.put("referencedObjectId", Long.toString(referenced.get().id()));
            foreignKey.put("referencedColumns", group.referencedColumns());
            foreignKeys.add(foreignKey);
        }
        Map<String, Object> table = new LinkedHashMap<>();
        table.put("objectId", Long.toString(object.id()));
        table.put("tableName", object.objectRaw());
        table.put("tableComment", object.tableComment());
        table.put("columns", columns);
        table.put("primaryKey", primaryKey);
        table.put("foreignKeys", foreignKeys);
        table.put("foreignKeysRestricted", restricted);
        return table;
    }

    private record ForeignKeyReference(String referencedTable, String referencedColumn) {}

    private static final class ForeignKeyGroup {
        private final List<String> columns = new ArrayList<>();
        private final List<String> referencedColumns = new ArrayList<>();

        void add(ForeignKeyReference reference, String column) {
            columns.add(column);
            referencedColumns.add(reference.referencedColumn());
        }

        List<String> columns() {
            return List.copyOf(columns);
        }

        List<String> referencedColumns() {
            return List.copyOf(referencedColumns);
        }
    }

    private static ForeignKeyReference parseForeignKey(String json) {
        String table = stringValue(json, json.indexOf("\"referencedTable\""));
        String column = stringValue(json, json.indexOf("\"referencedColumn\""));
        return table == null || column == null ? null : new ForeignKeyReference(table, column);
    }

    private static String stringValue(String json, int keyIndex) {
        if (keyIndex < 0) {
            return null;
        }
        int start = json.indexOf('"', json.indexOf(':', keyIndex));
        int end = start < 0 ? -1 : json.indexOf('"', start + 1);
        return start >= 0 && end > start ? json.substring(start + 1, end) : null;
    }

    // ---------------------------------------------------------------- shared helpers

    private PublishedSnapshotReader.Published loadUsable(long datasourceId) {
        PublishedSnapshotReader.Published published = snapshots.load(datasourceId);
        if (!published.usable()) {
            throw new DomainException(ErrorCode.SCHEMA_STALE);
        }
        return published;
    }

    private static void requireScope(McpIdentity identity, String scope) {
        if (!identity.scopes().contains(scope)) {
            throw new DomainException(ErrorCode.TOKEN_INVALID);
        }
    }

    private SecuritySnapshot freshSnapshot(McpIdentity identity) {
        SecuritySnapshot snapshot =
                security.read(
                        new SecurityStateReader.SubjectRequest(
                                identity.platformId(),
                                identity.userId(),
                                identity.credentialId().startsWith("jwt:")
                                        ? List.of(
                                                UUID.fromString(
                                                        identity.credentialId().substring(4)))
                                        : List.of()));
        if (!snapshot.isFresh(Instant.now())) {
            throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
        }
        return snapshot;
    }

    private static DatabaseEngine requireDialect(
            PublishedSnapshotReader.Published published, String schemaName) {
        DatabaseEngine engine;
        if ("MYSQL".equals(published.engine())) {
            engine = DatabaseEngine.MYSQL;
        } else if ("POSTGRESQL".equals(published.engine())) {
            engine = DatabaseEngine.POSTGRESQL;
        } else {
            throw new DomainException(ErrorCode.DIALECT_CONTEXT_INVALID);
        }
        if (engine == DatabaseEngine.MYSQL) {
            if (schemaName != null && !schemaName.isEmpty()) {
                throw new DomainException(ErrorCode.DIALECT_CONTEXT_INVALID);
            }
        } else if (schemaName == null || schemaName.isBlank()) {
            throw new DomainException(ErrorCode.DIALECT_CONTEXT_INVALID);
        }
        return engine;
    }

    private static PublishedSnapshotReader.Namespace resolveNamespace(
            PublishedSnapshotReader.Published published, String catalog, String schema) {
        boolean postgres = "POSTGRESQL".equals(published.engine());
        if (postgres && !published.databaseName().equals(catalog)) {
            throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        for (PublishedSnapshotReader.Namespace namespace : published.namespaces()) {
            if (postgres) {
                if (namespace.schemaNorm().equals(schema)) {
                    return namespace;
                }
            } else if (namespace.catalogNorm().equals(catalog)
                    && namespace.schemaNorm().equals(schema)) {
                return namespace;
            }
        }
        throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
    }

    private static String databaseName(
            PublishedSnapshotReader.Published published,
            PublishedSnapshotReader.Namespace namespace) {
        return "POSTGRESQL".equals(published.engine())
                ? published.databaseName()
                : namespace.catalogRaw();
    }

    private static PublishedSnapshotReader.TableObject findObject(
            PublishedSnapshotReader.Namespace namespace, String objectName) {
        for (PublishedSnapshotReader.TableObject object : namespace.objects()) {
            if (object.objectNorm().equals(objectName)) {
                return object;
            }
        }
        throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
    }

    private static ScopeTableBinder.RelationCatalog relationCatalog(
            PublishedSnapshotReader.Published published,
            PublishedSnapshotReader.Namespace namespace,
            DatabaseEngine engine) {
        String expectedCatalog =
                engine == DatabaseEngine.POSTGRESQL
                        ? published.databaseName()
                        : namespace.catalogNorm();
        return key -> {
            ResourceNamespace expected = key.namespace();
            if (expected.engine() != engine
                    || expected.datasourceId().value() != published.datasourceId()
                    || !expected.catalog().equals(expectedCatalog)
                    || !expected.schema().equals(namespace.schemaNorm())) {
                return Optional.empty();
            }
            for (PublishedSnapshotReader.TableObject object : namespace.objects()) {
                if (object.objectNorm().equals(key.objectName())) {
                    return Optional.of(
                            new ResourceRef(
                                    key,
                                    new ResourceId(object.id()),
                                    object.generation(),
                                    object.objectVersion(),
                                    published.snapshotId()));
                }
            }
            return Optional.empty();
        };
    }

    private static RelationColumns relationColumns(PublishedSnapshotReader.Published published) {
        return relation -> {
            for (PublishedSnapshotReader.Namespace namespace : published.namespaces()) {
                for (PublishedSnapshotReader.TableObject object : namespace.objects()) {
                    if (object.id() == relation.objectId().value()) {
                        return object.columns().stream()
                                .map(
                                        column ->
                                                new ColumnDef(
                                                        column.ordinal(),
                                                        column.columnNorm(),
                                                        column.dataType(),
                                                        column.nullable()))
                                .toList();
                    }
                }
            }
            throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
        };
    }

    private PoolHandle activatePool(
            PublishedSnapshotReader.Published published, DatabaseEngine engine) {
        char[] credential =
                cipher.decrypt(published.credentialCipher(), published.keyId()).toCharArray();
        try {
            return pools.activate(
                    new PoolKey(
                            published.datasourceId(),
                            published.configVersion(),
                            engine,
                            published.host(),
                            published.port(),
                            published.databaseName(),
                            published.username(),
                            published.configVersion()),
                    credential,
                    PoolLimits.of(published.poolMax()));
        } finally {
            java.util.Arrays.fill(credential, '\0');
        }
    }

    /**
     * Bounded wait for one execution slot. Each attempt takes a queue slot, then tries the user,
     * datasource and gateway gauges; a rejected attempt returns its queue slot before the next
     * attempt. The overall wait never exceeds the quota queue window, and the caller revalidates
     * the security state again immediately before the business connection is borrowed.
     */
    private QuotaLimiter.ExecutionPermit awaitExecution(long userId, long datasourceId) {
        long deadline = System.nanoTime() + QuotaLimiter.QUEUE_WINDOW_NANOS;
        while (true) {
            QuotaLimiter.QueuePermit queued = quotas.acquireQueue(userId);
            try {
                return quotas.acquireExecution(userId, datasourceId, queued);
            } catch (DomainException failure) {
                ErrorCode code = failure.errorCode();
                if (code != ErrorCode.USER_CONCURRENCY_LIMIT
                        && code != ErrorCode.DATASOURCE_CONCURRENCY_LIMIT
                        && code != ErrorCode.GATEWAY_BUSY) {
                    throw failure;
                }
                if (System.nanoTime() >= deadline) {
                    throw new DomainException(ErrorCode.GATEWAY_BUSY);
                }
                try {
                    Thread.sleep(25L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new DomainException(ErrorCode.GATEWAY_BUSY);
                }
            }
        }
    }

    private void terminal(
            UUID requestId,
            McpIdentity identity,
            Long datasourceId,
            Instant startedAt,
            AuditTerminal.Result result,
            AuditTerminal.Verdict verdict,
            String errorCode,
            Integer durationMs,
            Integer queueMs,
            Integer rowsReturned,
            boolean truncated,
            byte[] sqlFingerprint,
            String tool) {
        audit.terminal(
                new AuditTerminal(
                        requestId,
                        result,
                        verdict,
                        errorCode,
                        durationMs == null ? null : durationMs.longValue(),
                        queueMs,
                        rowsReturned,
                        truncated,
                        null,
                        sqlFingerprint,
                        null,
                        null,
                        tool,
                        identity.userId(),
                        identity.platformId(),
                        datasourceId,
                        startedAt));
    }

    private static AuditTerminal.Verdict verdictFor(Stage stage, ErrorCode code) {
        return switch (stage) {
            case GUARD -> AuditTerminal.Verdict.DENY;
            case EXECUTION, ENCODE ->
                    revalidationFailure(code)
                            ? AuditTerminal.Verdict.ERROR
                            : AuditTerminal.Verdict.ALLOW;
            case PRE_GUARD -> AuditTerminal.Verdict.ERROR;
        };
    }

    private static boolean revalidationFailure(ErrorCode code) {
        return code == ErrorCode.TOKEN_INVALID
                || code == ErrorCode.TOKEN_REVOKED
                || code == ErrorCode.SECURITY_STATE_UNAVAILABLE
                || code == ErrorCode.RESOURCE_VERSION_CHANGED;
    }

    // ---------------------------------------------------------------- response assembly

    private record Budgeted(Map<String, Object> structured, int rowsReturned, boolean truncated) {}

    private Budgeted fitRows(
            JsonNode id,
            UUID requestId,
            String datasourceId,
            String databaseName,
            String schemaName,
            ToolResultEncoder.Encoded encoded,
            int durationMs) {
        int rowCount = encoded.rows().size();
        while (true) {
            List<List<Object>> visible = encoded.rows().subList(0, rowCount);
            List<Map<String, Object>> cellTruncations = new ArrayList<>();
            for (Map<String, Object> truncation : encoded.cellTruncations()) {
                if (((Number) truncation.get("rowIndex")).intValue() < rowCount) {
                    cellTruncations.add(truncation);
                }
            }
            boolean truncated =
                    encoded.rowLimitReached()
                            || encoded.responseLimitReached()
                            || rowCount < encoded.rows().size();
            List<String> reasons = new ArrayList<>(4);
            if (encoded.rowLimitReached()) {
                reasons.add("ROW_LIMIT");
            }
            if (!cellTruncations.isEmpty()) {
                truncated = true;
                reasons.add("CELL_LIMIT");
            }
            if (encoded.responseLimitReached() || rowCount < encoded.rows().size()) {
                reasons.add("RESPONSE_LIMIT");
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("datasourceId", datasourceId);
            data.put("databaseName", databaseName);
            data.put("schemaName", schemaName == null || schemaName.isEmpty() ? null : schemaName);
            data.put("columns", encoded.columns());
            data.put("rows", visible);
            data.put("rowsReturned", rowCount);
            data.put("truncated", truncated);
            data.put("truncationReasons", reasons);
            data.put("cellTruncations", cellTruncations);
            data.put("durationMs", durationMs);
            Map<String, Object> structured = successContent(requestId, data);
            String body = envelope(id, callToolResult(false, structured));
            int bodyBytes = body.getBytes(StandardCharsets.UTF_8).length;
            if (bodyBytes <= RESPONSE_BUDGET_BYTES) {
                return new Budgeted(structured, rowCount, truncated);
            }
            if (rowCount == 0) {
                throw new DomainException(ErrorCode.RESULT_METADATA_TOO_LARGE);
            }
            int estimate = (int) ((long) rowCount * RESPONSE_BUDGET_BYTES / bodyBytes);
            rowCount = Math.max(0, Math.min(estimate, rowCount - 1));
        }
    }

    private static void requireWithinBudget(String body) {
        if (body.getBytes(StandardCharsets.UTF_8).length > RESPONSE_BUDGET_BYTES) {
            throw new DomainException(ErrorCode.RESULT_METADATA_TOO_LARGE);
        }
    }

    private static Map<String, Object> successContent(UUID requestId, Map<String, Object> data) {
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("requestId", requestId.toString());
        structured.put("status", "success");
        structured.put("data", data);
        return structured;
    }

    private static Map<String, Object> errorContent(UUID requestId, ErrorCode code, Stage stage) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code.name());
        error.put("message", McpErrorCatalog.message(code));
        error.put("retryable", McpErrorCatalog.retryable(code));
        error.put("details", Map.of());
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("requestId", requestId.toString());
        structured.put(
                "status",
                stage == Stage.GUARD || code == ErrorCode.TABLE_NOT_AUTHORIZED
                        ? "denied"
                        : "error");
        structured.put("error", error);
        return structured;
    }

    private static Map<String, Object> callToolResult(
            boolean isError, Map<String, Object> structured) {
        String text = JSON.writeValueAsString(structured);
        return Map.of(
                "isError", isError,
                "content", List.of(Map.of("type", "text", "text", text)),
                "structuredContent", structured);
    }

    private static Map<String, Object> initializeResult() {
        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", "ptmcp-gateway");
        serverInfo.put("version", "0.1.0");
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("tools", Map.of("listChanged", false));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", PROTOCOL_VERSION);
        result.put("capabilities", capabilities);
        result.put("serverInfo", serverInfo);
        return result;
    }

    private static String envelope(JsonNode id, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        return JSON.writeValueAsString(response);
    }

    private static String rpcError(JsonNode id, int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", Map.of("code", code, "message", message));
        return JSON.writeValueAsString(response);
    }

    private static List<Map<String, Object>> toolDescriptors() {
        return List.of(
                descriptor(
                        "list_databases_and_tables",
                        orderedMap(
                                "datasource_id", stringPattern(),
                                "database_name", optionalString(128),
                                "schema_name", optionalString(128),
                                "keyword", optionalString(128),
                                "cursor", optionalString(2048),
                                "page_size",
                                        orderedMap(
                                                "type",
                                                "integer",
                                                "minimum",
                                                1,
                                                "maximum",
                                                MAX_PAGE_SIZE)),
                        List.of("datasource_id")),
                descriptor(
                        "get_table_schema",
                        orderedMap(
                                "datasource_id", stringPattern(),
                                "database_name", stringSchema(1, 128),
                                "schema_name", optionalString(128),
                                "table_names",
                                        orderedMap(
                                                "type",
                                                "array",
                                                "minItems",
                                                1,
                                                "maxItems",
                                                20,
                                                "uniqueItems",
                                                true,
                                                "items",
                                                stringSchema(1, 128))),
                        List.of("datasource_id", "database_name", "table_names")),
                descriptor(
                        "execute_readonly_sql",
                        orderedMap(
                                "datasource_id", stringPattern(),
                                "database_name", stringSchema(1, 128),
                                "schema_name", optionalString(128),
                                "sql_query", orderedMap("type", "string", "minLength", 1),
                                "max_rows",
                                        orderedMap(
                                                "type",
                                                "integer",
                                                "minimum",
                                                1,
                                                "maximum",
                                                MAX_ROWS_LIMIT)),
                        List.of("datasource_id", "database_name", "sql_query")));
    }

    private static Map<String, Object> descriptor(
            String name, Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("additionalProperties", false);
        schema.put("required", required);
        schema.put("properties", properties);
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("name", name);
        descriptor.put("description", "");
        descriptor.put("inputSchema", schema);
        return descriptor;
    }

    private static Map<String, Object> orderedMap(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            map.put((String) pairs[index], pairs[index + 1]);
        }
        return map;
    }

    private static Map<String, Object> stringPattern() {
        return orderedMap("type", "string", "pattern", "^[0-9]+$");
    }

    private static Map<String, Object> stringSchema(int minLength, int maxLength) {
        return orderedMap("type", "string", "minLength", minLength, "maxLength", maxLength);
    }

    private static Map<String, Object> optionalString(int maxLength) {
        return orderedMap("type", "string", "maxLength", maxLength);
    }

    // ---------------------------------------------------------------- argument parsing

    private static String requiredText(JsonNode args, String field) {
        JsonNode value = args.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(field);
        }
        return value.asText();
    }

    private static String optionalText(JsonNode args, String field) {
        JsonNode value = args.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(field);
        }
        return value.asText();
    }

    private static int optionalInt(JsonNode args, String field, int fallback) {
        JsonNode value = args.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return fallback;
        }
        if (!value.isNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException(field);
        }
        return value.asInt();
    }

    private static List<String> textArray(JsonNode args, String field, int maxItems) {
        JsonNode value = args.path(field);
        if (!value.isArray() || value.isEmpty() || value.size() > maxItems) {
            throw new IllegalArgumentException(field);
        }
        Set<String> unique = new TreeSet<>();
        for (JsonNode entry : value) {
            if (!entry.isTextual() || entry.asText().isBlank()) {
                throw new IllegalArgumentException(field);
            }
            unique.add(entry.asText());
        }
        if (unique.size() != value.size()) {
            throw new IllegalArgumentException(field);
        }
        return List.copyOf(unique);
    }

    private static UUID parseRequestId(String candidate) {
        if (candidate != null) {
            try {
                return UUID.fromString(candidate);
            } catch (IllegalArgumentException ignored) {
                // Fall through to a fresh id; the boundary always owns the request identity.
            }
        }
        return UUID.randomUUID();
    }

    // ---------------------------------------------------------------- cursor helpers

    private record CursorPosition(long namespaceId, long objectId) {
        boolean before(long namespaceId, long objectId) {
            return namespaceId > this.namespaceId
                    || (namespaceId == this.namespaceId && objectId > this.objectId);
        }
    }

    private static CursorPosition decodeCursor(String cursor) {
        if (cursor == null) {
            return null;
        }
        try {
            String decoded =
                    new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
            int split = decoded.indexOf(':');
            if (split <= 0 || split == decoded.length() - 1) {
                throw new IllegalArgumentException("cursor");
            }
            return new CursorPosition(
                    Long.parseLong(decoded.substring(0, split)),
                    Long.parseLong(decoded.substring(split + 1)));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("cursor");
        }
    }

    private static String encodeCursor(long namespaceId, long objectId) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString((namespaceId + ":" + objectId).getBytes(StandardCharsets.US_ASCII));
    }

    private static int elapsedMillis(long startedNanos) {
        return (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - startedNanos) / 1_000_000);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException impossible) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR);
        }
    }
}
