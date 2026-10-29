package local.ptmcp.persistence.org;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

/** Reads and validates one authoritative department chain in a single metadata snapshot. */
final class DepartmentSubjectReader {
    private static final int MAX_NODES = 64;
    private static final String PATH_SQL =
            """
            WITH RECURSIVE department_path AS (
                SELECT p.dept_id AS assigned_dept_id, d.dept_id, d.parent_id,
                       d.status, d.del_flag, 1 AS hop
                FROM sys_user_profile p
                LEFT JOIN sys_dept d ON d.dept_id = p.dept_id
                WHERE p.user_id = ? AND p.status = 'ACTIVE'
                UNION ALL
                SELECT p.assigned_dept_id, d.dept_id, d.parent_id,
                       d.status, d.del_flag, p.hop + 1
                FROM department_path p
                JOIN sys_dept d ON d.dept_id = p.parent_id
                WHERE p.hop < 64 AND p.parent_id <> 0
            )
            SELECT assigned_dept_id, dept_id, parent_id, status, del_flag
            FROM department_path ORDER BY hop
            """;

    private DepartmentSubjectReader() {}

    record Departments(Long deptId, Set<Long> ancestorIds) {
        Departments {
            ancestorIds = Set.copyOf(ancestorIds);
        }
    }

    static Departments read(
            Connection connection, long userId, boolean inheritance, ErrorCode unavailableUser)
            throws SQLException {
        try (PreparedStatement statement = OrgSql.statement(connection, PATH_SQL)) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                Set<Long> seen = new HashSet<>();
                Set<Long> ancestors = new HashSet<>();
                Long direct = null;
                long expected = 0;
                while (result.next()) {
                    boolean first = seen.isEmpty();
                    if (first) {
                        expected = result.getLong(1);
                    }
                    Long dept = result.getObject(2, Long.class);
                    Long parent = result.getObject(3, Long.class);
                    if (dept == null
                            || dept <= 0
                            || dept != expected
                            || parent == null
                            || parent < 0
                            || !seen.add(dept)
                            || seen.size() > MAX_NODES) {
                        throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
                    }
                    if ("0".equals(result.getString(4)) && "0".equals(result.getString(5))) {
                        if (first) {
                            direct = dept;
                        } else if (inheritance) {
                            ancestors.add(dept);
                        }
                    }
                    expected = parent;
                }
                if (seen.isEmpty()) {
                    throw new DomainException(unavailableUser);
                }
                // Missing parents and the depth bound must never yield partial permissions.
                if (expected != 0) {
                    throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
                }
                return new Departments(direct, ancestors);
            }
        }
    }
}
