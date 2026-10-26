package local.ptmcp.admin.platform;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import javax.sql.DataSource;

/**
 * Read-only projection of {@code platform_registry} for the admin console. Proof configuration
 * never leaves storage; this directory only reports whether it is configured.
 */
public final class PlatformDirectoryService {
    private static final int MAX_PAGE = 100;
    private static final int DEFAULT_PAGE = 20;

    private final DataSource primary;

    public PlatformDirectoryService(DataSource primary) {
        this.primary = Objects.requireNonNull(primary, "primary");
    }

    public record PlatformView(
            String id,
            String code,
            String name,
            int tier,
            String status,
            String catalogMode,
            String proofMode,
            boolean proofConfigured,
            String scopeVersion,
            String securityVersion,
            String version) {}

    public record Page(List<PlatformView> items, String nextCursor) {}

    public Page list(String keyword, String status, Long afterId, Integer pageSize) {
        int limit = pageSize(pageSize);
        String normalizedStatus =
                status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT);
        if (normalizedStatus != null
                && !Set.of("ACTIVE", "DISABLED", "DELETED").contains(normalizedStatus)) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        String normalizedKeyword = keyword == null || keyword.isBlank() ? null : keyword.trim();
        if (normalizedKeyword != null && normalizedKeyword.length() > 64) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }

        StringBuilder sql =
                new StringBuilder(
                        "SELECT id, code, name, tier, status, catalog_mode, proof_mode,"
                                + " proof_config_cipher IS NOT NULL, scope_version,"
                                + " security_version FROM platform_registry WHERE 1 = 1");
        List<Object> parameters = new ArrayList<>();
        if (normalizedKeyword != null) {
            sql.append(" AND (code LIKE ? ESCAPE '\\\\' OR name LIKE ? ESCAPE '\\\\')");
            String pattern = "%" + escapeLike(normalizedKeyword) + "%";
            parameters.add(pattern);
            parameters.add(pattern);
        }
        if (normalizedStatus != null) {
            sql.append(" AND status = ?");
            parameters.add(normalizedStatus);
        }
        if (afterId != null) {
            sql.append(" AND id > ?");
            parameters.add(afterId);
        }
        sql.append(" ORDER BY id LIMIT ?");
        parameters.add(limit + 1);

        List<PlatformView> items = new ArrayList<>();
        String next = null;
        long lastKeptId = 0;
        try (Connection connection = primary.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            for (int index = 0; index < parameters.size(); index++) {
                statement.setObject(index + 1, parameters.get(index));
            }
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    long securityVersion = result.getLong(10);
                    long id = result.getLong(1);
                    items.add(
                            new PlatformView(
                                    Long.toString(id),
                                    result.getString(2),
                                    result.getString(3),
                                    result.getInt(4),
                                    result.getString(5),
                                    result.getString(6),
                                    result.getString(7),
                                    result.getBoolean(8),
                                    Long.toString(result.getLong(9)),
                                    Long.toString(securityVersion),
                                    Long.toString(securityVersion)));
                    if (items.size() <= limit) {
                        lastKeptId = id;
                    }
                }
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        if (items.size() > limit) {
            next = Long.toString(lastKeptId);
            items.subList(limit, items.size()).clear();
        }
        return new Page(List.copyOf(items), next);
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
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
}
