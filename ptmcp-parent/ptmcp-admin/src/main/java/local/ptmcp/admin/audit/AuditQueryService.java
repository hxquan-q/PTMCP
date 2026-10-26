package local.ptmcp.admin.audit;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import tools.jackson.databind.json.JsonMapper;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import javax.sql.DataSource;

/**
 * Shared read model over {@code audit_log} for the personal history portal and the platform-admin
 * audit console. Raw SQL ciphers, key ids and the WAL never cross this layer; every projection
 * exposes the masked statement only.
 */
public final class AuditQueryService {
    private static final int MAX_PAGE = 100;
    private static final int DEFAULT_PAGE = 20;
    private static final Duration MAX_WINDOW = Duration.ofDays(31);
    private static final Duration DEFAULT_WINDOW = Duration.ofDays(7);
    private static final ZoneOffset UTC = ZoneOffset.UTC;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> RESULTS = Set.of("RUNNING", "SUCCESS", "ERROR", "UNKNOWN");
    private static final Set<String> VERDICTS = Set.of("ALLOW", "DENY", "ERROR", "UNKNOWN");
    private static final String SUMMARY_COLUMNS =
            "request_id, tool, datasource_id, platform_id, user_id, started_at, completed_at,"
                    + " result, verdict, error_code, sql_masked, duration_ms, rows_returned,"
                    + " truncated";

    private final DataSource primary;

    public AuditQueryService(DataSource primary) {
        this.primary = Objects.requireNonNull(primary, "primary");
    }

    /** Personal history projection: identifiers keep the established portal shape. */
    public record AuditSummary(
            String requestId,
            String tool,
            Long datasourceId,
            Long platformId,
            Instant startedAt,
            Instant completedAt,
            String result,
            String verdict,
            String errorCode,
            String sqlMasked,
            Long durationMs,
            Integer rowsReturned,
            boolean truncated) {}

    public record AuditPage(List<AuditSummary> items, String nextCursor) {}

    public record AuditDetail(
            String requestId,
            String tool,
            Long datasourceId,
            Long platformId,
            Instant startedAt,
            Instant completedAt,
            String result,
            String verdict,
            String errorCode,
            String sqlMasked,
            Object resourceRefs,
            Object versions,
            Long durationMs,
            Integer queueMs,
            Integer dbMs,
            Integer rowsReturned,
            Integer responseBytes,
            boolean truncated) {}

    /** Admin console projection: identifiers follow the console string-id contract. */
    public record AdminAuditSummary(
            String requestId,
            String tool,
            String datasourceId,
            String platformId,
            String userId,
            Instant startedAt,
            Instant completedAt,
            String result,
            String verdict,
            String errorCode,
            String sqlMasked,
            Long durationMs,
            Integer rowsReturned,
            boolean truncated) {}

    public record AdminAuditPage(List<AdminAuditSummary> items, String nextCursor) {}

    public record AdminAuditDetail(
            String requestId,
            String tool,
            String datasourceId,
            String platformId,
            String userId,
            Instant startedAt,
            Instant completedAt,
            String result,
            String verdict,
            String errorCode,
            String sqlMasked,
            String fingerprint,
            Object resourceRefs,
            Object versions,
            Long durationMs,
            Integer queueMs,
            Integer dbMs,
            Integer rowsReturned,
            Integer responseBytes,
            boolean truncated) {}

    // ------------------------------------------------------------- personal history

    public AuditPage history(
            long userId,
            Instant from,
            Instant to,
            String tool,
            String result,
            String verdict,
            String cursor,
            Integer pageSize) {
        Window window = window(from, to);
        Filters filters =
                new Filters(
                        userId,
                        null,
                        null,
                        optionalFilter(tool, 64),
                        optionalEnum(result, RESULTS),
                        optionalEnum(verdict, VERDICTS),
                        null);
        Cursor decoded = decodeCursor(cursor);
        int limit = pageSize(pageSize);

        StringBuilder sql =
                new StringBuilder(
                        "SELECT request_id, tool, datasource_id, platform_id, started_at,"
                                + " completed_at, result, verdict, error_code, sql_masked,"
                                + " duration_ms, rows_returned, truncated FROM audit_log"
                                + " WHERE user_id = ? AND started_at >= ? AND started_at <= ?");
        List<Object> parameters = new ArrayList<>();
        parameters.add(userId);
        parameters.add(window.startUtc());
        parameters.add(window.endUtc());
        appendFilters(sql, parameters, filters);
        appendCursorAndOrder(sql, parameters, decoded, limit);

        List<AuditSummary> items = new ArrayList<>();
        String next = null;
        try (Connection connection = primary.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bind(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    items.add(
                            new AuditSummary(
                                    resultSet.getString(1),
                                    resultSet.getString(2),
                                    nullableLong(resultSet, 3),
                                    nullableLong(resultSet, 4),
                                    toInstant(resultSet.getObject(5, LocalDateTime.class)),
                                    toInstant(resultSet.getObject(6, LocalDateTime.class)),
                                    resultSet.getString(7),
                                    resultSet.getString(8),
                                    resultSet.getString(9),
                                    mask(resultSet.getString(10)),
                                    nullableLong(resultSet, 11),
                                    nullableInt(resultSet, 12),
                                    resultSet.getInt(13) == 1));
                }
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        if (items.size() > limit) {
            AuditSummary last = items.get(limit - 1);
            next = encodeCursor(last.startedAt(), last.requestId());
            items.subList(limit, items.size()).clear();
        }
        return new AuditPage(List.copyOf(items), next);
    }

    public AuditDetail historyDetail(long userId, String requestId) {
        String parsed = parseRequestId(requestId);
        try (Connection connection = primary.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT request_id, tool, datasource_id, platform_id, started_at,"
                                        + " completed_at, result, verdict, error_code, sql_masked,"
                                        + " resource_refs, versions, duration_ms, queue_ms, db_ms,"
                                        + " rows_returned, response_bytes, truncated FROM audit_log"
                                        + " WHERE request_id = ? AND user_id = ?")) {
            statement.setString(1, parsed);
            statement.setLong(2, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
                }
                return personalDetail(result);
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    // ------------------------------------------------------------- admin console

    public AdminAuditPage adminHistory(
            Instant from,
            Instant to,
            Long userId,
            Long platformId,
            Long datasourceId,
            String tool,
            String result,
            String verdict,
            String errorCode,
            String cursor,
            Integer pageSize) {
        Window window = window(from, to);
        Filters filters =
                new Filters(
                        userId,
                        platformId,
                        datasourceId,
                        optionalFilter(tool, 64),
                        optionalEnum(result, RESULTS),
                        optionalEnum(verdict, VERDICTS),
                        optionalFilter(errorCode, 64));
        Cursor decoded = decodeCursor(cursor);
        int limit = pageSize(pageSize);

        StringBuilder sql =
                new StringBuilder(
                        "SELECT "
                                + SUMMARY_COLUMNS
                                + " FROM audit_log"
                                + " WHERE started_at >= ? AND started_at <= ?");
        List<Object> parameters = new ArrayList<>();
        parameters.add(window.startUtc());
        parameters.add(window.endUtc());
        appendFilters(sql, parameters, filters);
        appendCursorAndOrder(sql, parameters, decoded, limit);

        List<AdminAuditSummary> items = new ArrayList<>();
        String next = null;
        try (Connection connection = primary.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            bind(statement, parameters);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    items.add(
                            new AdminAuditSummary(
                                    resultSet.getString(1),
                                    resultSet.getString(2),
                                    nullableString(resultSet, 3),
                                    nullableString(resultSet, 4),
                                    nullableString(resultSet, 5),
                                    toInstant(resultSet.getObject(6, LocalDateTime.class)),
                                    toInstant(resultSet.getObject(7, LocalDateTime.class)),
                                    resultSet.getString(8),
                                    resultSet.getString(9),
                                    resultSet.getString(10),
                                    mask(resultSet.getString(11)),
                                    nullableLong(resultSet, 12),
                                    nullableInt(resultSet, 13),
                                    resultSet.getInt(14) == 1));
                }
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        if (items.size() > limit) {
            AdminAuditSummary last = items.get(limit - 1);
            next = encodeCursor(last.startedAt(), last.requestId());
            items.subList(limit, items.size()).clear();
        }
        return new AdminAuditPage(List.copyOf(items), next);
    }

    public record SensitiveAccessRow(
            long id, long operatorId, String reason, List<String> fields, Instant createdAt) {}

    /** Who revealed the plaintext of this request, when and why — the AC audit-trail read. */
    public List<SensitiveAccessRow> sensitiveAccess(String requestId) {
        String parsed = parseRequestId(requestId);
        String sql =
                "SELECT id, operator_id, reason, fields, create_time FROM audit_sensitive_access"
                        + " WHERE request_id = ? ORDER BY id DESC LIMIT 100";
        List<SensitiveAccessRow> rows = new ArrayList<>();
        try (Connection connection = primary.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(5);
            statement.setString(1, parsed);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    List<String> fields = List.of();
                    String raw = result.getString(4);
                    if (raw != null && !raw.isBlank()) {
                        String trimmed = raw.trim();
                        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                            String body = trimmed.substring(1, trimmed.length() - 1).trim();
                            if (!body.isEmpty()) {
                                List<String> parsedFields = new ArrayList<>();
                                for (String part : body.split(",")) {
                                    String value = part.trim();
                                    if (value.length() >= 2
                                            && value.startsWith("\"")
                                            && value.endsWith("\"")) {
                                        parsedFields.add(value.substring(1, value.length() - 1));
                                    }
                                }
                                fields = List.copyOf(parsedFields);
                            }
                        }
                    }
                    rows.add(
                            new SensitiveAccessRow(
                                    result.getLong(1),
                                    result.getLong(2),
                                    result.getString(3),
                                    fields,
                                    toInstant(result.getObject(5, java.time.LocalDateTime.class))));
                }
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        return List.copyOf(rows);
    }

    public AdminAuditDetail adminDetail(String requestId) {
        String parsed = parseRequestId(requestId);
        try (Connection connection = primary.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT request_id, tool, datasource_id, platform_id, user_id,"
                                        + " started_at, completed_at, result, verdict, error_code,"
                                        + " sql_masked, sql_fingerprint, resource_refs, versions,"
                                        + " duration_ms, queue_ms, db_ms, rows_returned,"
                                        + " response_bytes, truncated FROM audit_log"
                                        + " WHERE request_id = ?")) {
            statement.setString(1, parsed);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
                }
                return adminDetail(result);
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    // ------------------------------------------------------------- internals

    private record Filters(
            Long userId,
            Long platformId,
            Long datasourceId,
            String tool,
            String result,
            String verdict,
            String errorCode) {}

    private record Window(LocalDateTime startUtc, LocalDateTime endUtc) {}

    private record Cursor(Instant startedAt, String requestId) {}

    private static Window window(Instant from, Instant to) {
        Instant end = to == null ? Instant.now() : to;
        Instant start = from == null ? end.minus(DEFAULT_WINDOW) : from;
        if (!start.isBefore(end) || Duration.between(start, end).compareTo(MAX_WINDOW) > 0) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        return new Window(utc(start), utc(end));
    }

    private static void appendFilters(StringBuilder sql, List<Object> parameters, Filters filters) {
        if (filters.userId() != null) {
            sql.append(" AND user_id = ?");
            parameters.add(filters.userId());
        }
        if (filters.platformId() != null) {
            sql.append(" AND platform_id = ?");
            parameters.add(filters.platformId());
        }
        if (filters.datasourceId() != null) {
            sql.append(" AND datasource_id = ?");
            parameters.add(filters.datasourceId());
        }
        if (filters.tool() != null) {
            sql.append(" AND tool = ?");
            parameters.add(filters.tool());
        }
        if (filters.result() != null) {
            sql.append(" AND result = ?");
            parameters.add(filters.result());
        }
        if (filters.verdict() != null) {
            sql.append(" AND verdict = ?");
            parameters.add(filters.verdict());
        }
        if (filters.errorCode() != null) {
            sql.append(" AND error_code = ?");
            parameters.add(filters.errorCode());
        }
    }

    private static void appendCursorAndOrder(
            StringBuilder sql, List<Object> parameters, Cursor decoded, int limit) {
        if (decoded != null) {
            sql.append(" AND (started_at < ? OR (started_at = ? AND request_id < ?))");
            parameters.add(utc(decoded.startedAt()));
            parameters.add(utc(decoded.startedAt()));
            parameters.add(decoded.requestId());
        }
        sql.append(" ORDER BY started_at DESC, request_id DESC LIMIT ?");
        parameters.add(limit + 1);
    }

    private static AuditDetail personalDetail(ResultSet result) throws SQLException {
        return new AuditDetail(
                result.getString(1),
                result.getString(2),
                nullableLong(result, 3),
                nullableLong(result, 4),
                toInstant(result.getObject(5, LocalDateTime.class)),
                toInstant(result.getObject(6, LocalDateTime.class)),
                result.getString(7),
                result.getString(8),
                result.getString(9),
                mask(result.getString(10)),
                parseJson(result.getString(11)),
                parseJson(result.getString(12)),
                nullableLong(result, 13),
                nullableInt(result, 14),
                nullableInt(result, 15),
                nullableInt(result, 16),
                nullableInt(result, 17),
                result.getInt(18) == 1);
    }

    private static AdminAuditDetail adminDetail(ResultSet result) throws SQLException {
        return new AdminAuditDetail(
                result.getString(1),
                result.getString(2),
                nullableString(result, 3),
                nullableString(result, 4),
                nullableString(result, 5),
                toInstant(result.getObject(6, LocalDateTime.class)),
                toInstant(result.getObject(7, LocalDateTime.class)),
                result.getString(8),
                result.getString(9),
                result.getString(10),
                mask(result.getString(11)),
                fingerprint(result.getBytes(12)),
                parseJson(result.getString(13)),
                parseJson(result.getString(14)),
                nullableLong(result, 15),
                nullableInt(result, 16),
                nullableInt(result, 17),
                nullableInt(result, 18),
                nullableInt(result, 19),
                result.getInt(20) == 1);
    }

    private static String fingerprint(byte[] value) {
        return value == null ? null : HexFormat.of().formatHex(value);
    }

    private static String parseRequestId(String requestId) {
        try {
            return java.util.UUID.fromString(requestId == null ? "" : requestId.trim()).toString();
        } catch (IllegalArgumentException failure) {
            throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    private static String mask(String masked) {
        if (masked != null && masked.length() > 512) {
            return masked.substring(0, 512) + "…";
        }
        return masked;
    }

    private static String encodeCursor(Instant startedAt, String requestId) {
        String raw = startedAt.toString() + "|" + requestId;
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static Cursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String raw =
                    new String(
                            Base64.getUrlDecoder().decode(cursor.trim()),
                            java.nio.charset.StandardCharsets.UTF_8);
            int separator = raw.lastIndexOf('|');
            if (separator <= 0) {
                throw new IllegalArgumentException("missing separator");
            }
            return new Cursor(
                    Instant.parse(raw.substring(0, separator)), raw.substring(separator + 1));
        } catch (IllegalArgumentException | DateTimeParseException failure) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
    }

    private static Object parseJson(String json) {
        try {
            return json == null || json.isBlank() ? null : JSON.readValue(json, Object.class);
        } catch (Exception failure) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private static void bind(PreparedStatement statement, List<Object> parameters)
            throws SQLException {
        for (int index = 0; index < parameters.size(); index++) {
            statement.setObject(index + 1, parameters.get(index));
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

    private static String nullableString(ResultSet result, int column) throws SQLException {
        long value = result.getLong(column);
        return result.wasNull() ? null : Long.toString(value);
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

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, UTC);
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(UTC);
    }
}
