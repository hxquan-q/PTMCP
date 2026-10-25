package local.ptmcp.persistence.audit;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import javax.sql.DataSource;

/**
 * Read-side store for the admin audit surface (audit.md §5, api-spec §5). List and detail never
 * return ciphertext columns; the decrypt path reads them only inside the same transaction that
 * persists the sensitive-access audit row, so an allowed decrypt is durable before any plaintext
 * leaves the store. Retention (180 days from startedAt) and the 31-day list window are enforced
 * here, in the query — callers never post-filter.
 */
public final class AuditAdminStore {
    public static final String FIELD_SQL_RAW = "sqlRaw";
    public static final String FIELD_SQL_FINAL = "sqlFinal";
    public static final int MAX_PAGE_SIZE = 200;
    public static final int MAX_LIST_WINDOW_DAYS = 31;
    public static final long RETENTION_DAYS = 180;

    private final DataSource primary;

    public AuditAdminStore(DataSource primary) {
        this.primary = Objects.requireNonNull(primary, "primary");
    }

    public record AuditSummaryRow(
            UUID requestId,
            Long userId,
            Long platformId,
            Long datasourceId,
            String tool,
            Instant startedAt,
            Instant completedAt,
            String result,
            String verdict,
            String errorCode) {}

    public record AuditDetailRow(
            UUID requestId,
            Long userId,
            Long platformId,
            Long datasourceId,
            String tool,
            Instant startedAt,
            Instant completedAt,
            String result,
            String verdict,
            String errorCode,
            String sqlMasked,
            Integer rawLength,
            Long durationMs,
            Integer queueMs,
            Integer rowsReturned,
            boolean truncated,
            byte[] sqlRawCipher,
            byte[] sqlFinalCipher,
            String keyId) {

        public AuditDetailRow {
            if (sqlRawCipher != null) {
                sqlRawCipher = sqlRawCipher.clone();
            }
            if (sqlFinalCipher != null) {
                sqlFinalCipher = sqlFinalCipher.clone();
            }
        }

        @Override
        public byte[] sqlRawCipher() {
            return sqlRawCipher == null ? null : sqlRawCipher.clone();
        }

        @Override
        public byte[] sqlFinalCipher() {
            return sqlFinalCipher == null ? null : sqlFinalCipher.clone();
        }
    }

    /** Cursor: the last row's (started_at, request_id); null means from the beginning. */
    public record Cursor(Instant startedAt, UUID requestId) {}

    public List<AuditSummaryRow> list(
            Instant from,
            Instant to,
            Long userId,
            Long platformId,
            Long datasourceId,
            String tool,
            String result,
            String verdict,
            String errorCode,
            Cursor cursor,
            int pageSize) {
        validateWindow(from, to);
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        StringBuilder sql =
                new StringBuilder(
                        "SELECT request_id, user_id, platform_id, datasource_id, tool, started_at,"
                                + " completed_at, result, verdict, error_code"
                                + " FROM audit_log WHERE started_at >= ? AND started_at <= ?");
        List<Object> parameters = new ArrayList<>();
        parameters.add(toTimestamp(from));
        parameters.add(toTimestamp(to));
        if (userId != null) {
            sql.append(" AND user_id = ?");
            parameters.add(userId);
        }
        if (platformId != null) {
            sql.append(" AND platform_id = ?");
            parameters.add(platformId);
        }
        if (datasourceId != null) {
            sql.append(" AND datasource_id = ?");
            parameters.add(datasourceId);
        }
        if (tool != null) {
            sql.append(" AND tool = ?");
            parameters.add(tool);
        }
        if (result != null) {
            sql.append(" AND result = ?");
            parameters.add(result);
        }
        if (verdict != null) {
            sql.append(" AND verdict = ?");
            parameters.add(verdict);
        }
        if (errorCode != null) {
            sql.append(" AND error_code = ?");
            parameters.add(errorCode);
        }
        if (cursor != null) {
            sql.append(" AND (started_at, request_id) < (?, ?)");
            parameters.add(toTimestamp(cursor.startedAt()));
            parameters.add(cursor.requestId().toString());
        }
        sql.append(" ORDER BY started_at DESC, request_id DESC LIMIT ?");
        parameters.add(pageSize);
        try (Connection connection = primary.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int index = 0; index < parameters.size(); index++) {
                statement.setObject(index + 1, parameters.get(index));
            }
            try (ResultSet result_ = statement.executeQuery()) {
                List<AuditSummaryRow> rows = new ArrayList<>();
                while (result_.next()) {
                    rows.add(summary(result_));
                }
                return List.copyOf(rows);
            }
        } catch (SQLException failure) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    /** Full detail without ciphertext columns; a missing or retention-expired record is 404. */
    public AuditDetailRow detail(UUID requestId, Instant now) {
        AuditDetailRow row = loadDetail(requestId);
        if (row == null || retentionExpired(row.startedAt(), now)) {
            throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return row;
    }

    /** Raw decrypt inputs: cipher columns + key id + startedAt; retention-expired is 404. */
    public AuditDetailRow loadForDecrypt(UUID requestId, Instant now) {
        return detail(requestId, now);
    }

    /**
     * Persists the sensitive-access audit row (decision ALLOW or DENY) and commits before the
     * caller may see any plaintext (audit.md §5 — 解密访问先持久审计).
     */
    public void recordSensitiveAccess(
            UUID requestId, long operatorId, String reason, List<String> fields, String decision) {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(fields, "fields");
        Objects.requireNonNull(decision, "decision");
        if (!reason.matches("[\\s\\S]{10,500}")) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (!"ALLOW".equals(decision) && !"DENY".equals(decision)) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        String json = encodeFields(fields);
        try (Connection connection = primary.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement =
                        connection.prepareStatement(
                                "INSERT INTO audit_sensitive_access (request_id, operator_id,"
                                        + " reason, fields, decision, create_time)"
                                        + " VALUES (?, ?, ?, ?, ?, UTC_TIMESTAMP(6))")) {
                    statement.setString(1, requestId.toString());
                    statement.setLong(2, operatorId);
                    statement.setString(3, reason);
                    statement.setString(4, json);
                    statement.setString(5, decision);
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException failure) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private static String encodeFields(List<String> fields) {
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < fields.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append('"').append(fields.get(index)).append('"');
        }
        return json.append(']').toString();
    }

    private static void validateWindow(Instant from, Instant to) {
        if (from == null || to == null || !to.isAfter(from)) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (to.toEpochMilli() - from.toEpochMilli() > MAX_LIST_WINDOW_DAYS * 24L * 60 * 60 * 1000) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
    }

    private static boolean retentionExpired(Instant startedAt, Instant now) {
        return startedAt.plusSeconds(RETENTION_DAYS * 24 * 60 * 60).isBefore(now);
    }

    private AuditDetailRow loadDetail(UUID requestId) {
        String sql =
                "SELECT request_id, user_id, platform_id, datasource_id, tool, started_at,"
                        + " completed_at, result, verdict, error_code, sql_masked, raw_length,"
                        + " duration_ms, queue_ms, rows_returned, truncated, sql_raw_cipher,"
                        + " sql_final_cipher, key_id FROM audit_log WHERE request_id = ?";
        try (Connection connection = primary.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            statement.setString(1, requestId.toString());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                java.sql.Timestamp completedAt = result.getTimestamp("completed_at");
                byte[] rawCipher = result.getBytes("sql_raw_cipher");
                byte[] finalCipher = result.getBytes("sql_final_cipher");
                return new AuditDetailRow(
                        UUID.fromString(result.getString("request_id")),
                        (Long) result.getObject("user_id"),
                        (Long) result.getObject("platform_id"),
                        (Long) result.getObject("datasource_id"),
                        result.getString("tool"),
                        toInstant(result.getTimestamp("started_at")),
                        completedAt == null ? null : completedAt.toInstant(),
                        result.getString("result"),
                        result.getString("verdict"),
                        result.getString("error_code"),
                        result.getString("sql_masked"),
                        (Integer) result.getObject("raw_length"),
                        (Long) result.getObject("duration_ms"),
                        (Integer) result.getObject("queue_ms"),
                        (Integer) result.getObject("rows_returned"),
                        result.getInt("truncated") == 1,
                        rawCipher,
                        finalCipher,
                        result.getString("key_id"));
            }
        } catch (SQLException failure) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private static AuditSummaryRow summary(ResultSet result) throws SQLException {
        java.sql.Timestamp startedAt = result.getTimestamp("started_at");
        java.sql.Timestamp completedAt = result.getTimestamp("completed_at");
        Long userId = (Long) result.getObject("user_id");
        Long platformId = (Long) result.getObject("platform_id");
        Long datasourceId = (Long) result.getObject("datasource_id");
        return new AuditSummaryRow(
                UUID.fromString(result.getString("request_id")),
                userId,
                platformId,
                datasourceId,
                result.getString("tool"),
                startedAt.toInstant(),
                completedAt == null ? null : completedAt.toInstant(),
                result.getString("result"),
                result.getString("verdict"),
                result.getString("error_code"));
    }

    private static Instant toInstant(java.sql.Timestamp timestamp) {
        return timestamp.toInstant();
    }

    private static java.sql.Timestamp toTimestamp(Instant instant) {
        return java.sql.Timestamp.from(instant);
    }
}
