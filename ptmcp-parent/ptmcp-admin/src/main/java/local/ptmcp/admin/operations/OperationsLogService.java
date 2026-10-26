package local.ptmcp.admin.operations;

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
import java.util.Set;

import javax.sql.DataSource;

/**
 * Read model for {@code admin_operation_log}: the durable "who changed what" trail written by the
 * delegation, platform, client and identity slices. Platform-admin only, cursor paged (id
 * descending), filterable by action and aggregate. The masked before/after payloads stay masked —
 * this read never becomes a second way into sensitive material.
 */
public final class OperationsLogService {
    private static final int MAX_PAGE = 100;
    private static final int DEFAULT_PAGE = 20;
    private static final Set<String> AGGREGATE_TYPES =
            Set.of("ADMIN_DELEGATION", "PLATFORM", "OAUTH_CLIENT", "USER");

    private final DataSource primary;

    public OperationsLogService(DataSource primary) {
        this.primary = Objects.requireNonNull(primary, "primary");
    }

    public record OperationsRow(
            long id,
            long actorId,
            String requestId,
            String action,
            String aggregateType,
            String aggregateId,
            String beforeMasked,
            String afterMasked,
            Instant occurredAt) {}

    public record OperationsPage(List<OperationsRow> items, String nextCursor) {}

    public OperationsPage list(
            String action, String aggregateType, Long actorId, String cursor, Integer pageSize) {
        int limit = pageSize == null ? DEFAULT_PAGE : pageSize;
        if (limit < 1 || limit > MAX_PAGE) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (action != null && (action.isBlank() || action.length() > 64)) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (aggregateType != null && !AGGREGATE_TYPES.contains(aggregateType)) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        Long cursorId = null;
        if (cursor != null && !cursor.isBlank()) {
            try {
                cursorId = Long.parseLong(cursor);
            } catch (NumberFormatException failure) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
            if (cursorId <= 0) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
        }

        StringBuilder sql =
                new StringBuilder(
                        "SELECT id, actor_id, request_id, action, aggregate_type, aggregate_id,"
                                + " before_masked, after_masked, occurred_at FROM admin_operation_log"
                                + " WHERE 1 = 1");
        List<Object> parameters = new ArrayList<>();
        if (action != null && !action.isBlank()) {
            sql.append(" AND action = ?");
            parameters.add(action.trim());
        }
        if (aggregateType != null) {
            sql.append(" AND aggregate_type = ?");
            parameters.add(aggregateType);
        }
        if (actorId != null) {
            if (actorId <= 0) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
            sql.append(" AND actor_id = ?");
            parameters.add(actorId);
        }
        if (cursorId != null) {
            sql.append(" AND id < ?");
            parameters.add(cursorId);
        }
        sql.append(" ORDER BY id DESC LIMIT ?");
        parameters.add(limit + 1);

        List<OperationsRow> items = new ArrayList<>();
        String next = null;
        try (Connection connection = primary.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            statement.setQueryTimeout(5);
            for (int index = 0; index < parameters.size(); index++) {
                statement.setObject(index + 1, parameters.get(index));
            }
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    items.add(row(result));
                }
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        if (items.size() > limit) {
            next = Long.toString(items.get(limit - 1).id());
            items.subList(limit, items.size()).clear();
        }
        return new OperationsPage(List.copyOf(items), next);
    }

    private static OperationsRow row(ResultSet result) throws SQLException {
        return new OperationsRow(
                result.getLong(1),
                result.getLong(2),
                result.getString(3),
                result.getString(4),
                result.getString(5),
                result.getString(6),
                result.getString(7),
                result.getString(8),
                result.getObject(9, java.time.LocalDateTime.class) == null
                        ? null
                        : result.getObject(9, java.time.LocalDateTime.class)
                                .toInstant(java.time.ZoneOffset.UTC));
    }
}
