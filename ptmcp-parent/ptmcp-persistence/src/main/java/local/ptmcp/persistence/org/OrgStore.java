package local.ptmcp.persistence.org;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.SecurityChangeTransaction;
import local.ptmcp.persistence.SecurityEvent;
import local.ptmcp.persistence.security.UserSecurityStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import javax.sql.DataSource;

/**
 * Admin CRUD over the organization adaptation tables ({@code sys_dept}, {@code sys_role}, {@code
 * sys_role_permission}, {@code sys_user_login_name}, {@code sys_user_profile}, {@code
 * sys_user_role}). Deletes are logical ({@code del_flag}); every mutation is optimistic (version
 * bump guarded by {@code WHERE version = ?}) and reference checks fail closed with {@code
 * REFERENCES_EXIST} instead of cascading.
 */
public final class OrgStore {
    private static final Pattern ROLE_KEY = Pattern.compile("[a-z][a-z0-9_]{0,99}");

    private final DataSource primary;

    public OrgStore(DataSource primary) {
        this.primary = Objects.requireNonNull(primary, "primary");
    }

    public record DepartmentRow(
            long id, Long parentId, String name, boolean enabled, long version, String ancestors) {}

    public record RoleRow(
            long id,
            String key,
            String name,
            boolean enabled,
            List<String> permissionCodes,
            long version) {}

    public record UserRow(
            long id,
            String username,
            String displayName,
            long deptId,
            String email,
            String phoneNumber,
            String remark,
            boolean enabled,
            List<Long> roleIds,
            long version) {}

    // ---- departments -------------------------------------------------------

    public DepartmentRow createDepartment(
            Long parentId, String name, boolean enabled, long actorId) {
        OrgSql.requireText(name, 30);
        OrgSql.requireActor(actorId);
        try (Connection connection = OrgSql.configure(primary)) {
            connection.setAutoCommit(false);
            try {
                String ancestors;
                long storedParent;
                if (parentId == null) {
                    ancestors = "";
                    storedParent = 0L;
                } else {
                    DepartmentRow parent = lockDepartment(connection, parentId);
                    ancestors =
                            parent.ancestors().isEmpty()
                                    ? String.valueOf(parent.id())
                                    : parent.ancestors() + "," + parent.id();
                    storedParent = parent.id();
                }
                long id;
                try (PreparedStatement statement =
                        OrgSql.statement(
                                connection,
                                "INSERT INTO sys_dept (parent_id, ancestors, dept_name, status, del_flag,"
                                        + " create_time, update_time) VALUES (?, ?, ?, ?, '0', ?, ?)",
                                Statement.RETURN_GENERATED_KEYS)) {
                    statement.setLong(1, storedParent);
                    statement.setString(2, ancestors);
                    statement.setString(3, name);
                    statement.setString(4, enabled ? "0" : "1");
                    statement.setObject(5, OrgSql.nowUtc());
                    statement.setObject(6, OrgSql.nowUtc());
                    statement.executeUpdate();
                    id = generatedKey(statement);
                }
                writeOperationLog(
                        connection,
                        actorId,
                        "DEPARTMENT_CREATE",
                        "DEPARTMENT",
                        id,
                        "{\"name\":\"" + name + "\",\"enabled\":" + enabled + "}");
                connection.commit();
                return getDepartment(id);
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    // Never mask the in-flight failure with a rollback error.
                }
                throw failure;
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    public DepartmentRow updateDepartment(
            long id, String name, boolean enabled, long expectedVersion, long actorId) {
        OrgSql.requireText(name, 30);
        OrgSql.requireActor(actorId);
        requireVersion(expectedVersion);
        try (Connection connection = OrgSql.configure(primary)) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement =
                        OrgSql.statement(
                                connection,
                                "UPDATE sys_dept SET dept_name = ?, status = ?, version = version + 1,"
                                        + " update_time = ? WHERE dept_id = ? AND version = ?"
                                        + " AND del_flag = '0'")) {
                    statement.setString(1, name);
                    statement.setString(2, enabled ? "0" : "1");
                    statement.setObject(3, OrgSql.nowUtc());
                    statement.setLong(4, id);
                    statement.setLong(5, expectedVersion);
                    if (statement.executeUpdate() != 1) {
                        throw conflictOrMissing(connection, id);
                    }
                }
                writeOperationLog(
                        connection,
                        actorId,
                        "DEPARTMENT_UPDATE",
                        "DEPARTMENT",
                        id,
                        "{\"name\":\"" + name + "\",\"enabled\":" + enabled + "}");
                connection.commit();
                return getDepartment(id);
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    failure.addSuppressed(rollbackError);
                }
                throw failure;
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    public DepartmentRow moveDepartment(
            long id, Long newParentId, long expectedVersion, long actorId) {
        OrgSql.requireActor(actorId);
        requireVersion(expectedVersion);
        try (Connection connection = OrgSql.configure(primary)) {
            connection.setAutoCommit(false);
            try {
                DepartmentRow self = lockDepartment(connection, id);
                String newAncestors;
                long storedParent;
                if (newParentId == null) {
                    newAncestors = "";
                    storedParent = 0L;
                } else {
                    if (newParentId == id) {
                        throw new DomainException(ErrorCode.INVALID_ARGUMENT);
                    }
                    DepartmentRow parent = lockDepartment(connection, newParentId);
                    if (ancestorChain(parent.ancestors(), parent.id()).contains(id)) {
                        throw new DomainException(ErrorCode.INVALID_ARGUMENT);
                    }
                    newAncestors =
                            parent.ancestors().isEmpty()
                                    ? String.valueOf(parent.id())
                                    : parent.ancestors() + "," + parent.id();
                    storedParent = parent.id();
                }
                String oldAncestors = self.ancestors();
                try (PreparedStatement statement =
                        OrgSql.statement(
                                connection,
                                "UPDATE sys_dept SET parent_id = ?, ancestors = ?, version = version + 1,"
                                        + " update_time = ? WHERE dept_id = ? AND version = ?")) {
                    statement.setLong(1, storedParent);
                    statement.setString(2, newAncestors);
                    statement.setObject(3, OrgSql.nowUtc());
                    statement.setLong(4, id);
                    statement.setLong(5, expectedVersion);
                    if (statement.executeUpdate() != 1) {
                        throw new DomainException(ErrorCode.VERSION_CONFLICT);
                    }
                }
                String newPrefix =
                        newAncestors.isEmpty() ? String.valueOf(id) : newAncestors + "," + id;
                List<long[]> descendants = new ArrayList<>();
                List<String> chains = new ArrayList<>();
                try (PreparedStatement statement =
                        OrgSql.statement(
                                connection,
                                "SELECT dept_id, ancestors FROM sys_dept"
                                        + " WHERE del_flag = '0' AND dept_id <> ?")) {
                    statement.setLong(1, id);
                    try (ResultSet result = statement.executeQuery()) {
                        while (result.next()) {
                            long otherId = result.getLong(1);
                            String chain = result.getString(2);
                            if (ancestorChain(chain, otherId).contains(id)) {
                                descendants.add(new long[] {otherId});
                                chains.add(chain);
                            }
                        }
                    }
                }
                for (int index = 0; index < descendants.size(); index++) {
                    String rebuilt = rebuildChain(chains.get(index), id, newPrefix);
                    try (PreparedStatement statement =
                            OrgSql.statement(
                                    connection,
                                    "UPDATE sys_dept SET ancestors = ?, version = version + 1,"
                                            + " update_time = ? WHERE dept_id = ?")) {
                        statement.setString(1, rebuilt);
                        statement.setObject(2, OrgSql.nowUtc());
                        statement.setLong(3, descendants.get(index)[0]);
                        statement.executeUpdate();
                    }
                }
                writeOperationLog(
                        connection,
                        actorId,
                        "DEPARTMENT_MOVE",
                        "DEPARTMENT",
                        id,
                        "{\"descendantsMoved\":" + descendants.size() + "}");
                connection.commit();
                return getDepartment(id);
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    // Never mask the in-flight failure with a rollback error.
                }
                throw failure;
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    public void deleteDepartment(long id, long expectedVersion, long actorId) {
        OrgSql.requireActor(actorId);
        requireVersion(expectedVersion);
        try (Connection connection = OrgSql.configure(primary)) {
            connection.setAutoCommit(false);
            try {
                lockDepartment(connection, id);
                try (PreparedStatement children =
                                OrgSql.statement(
                                        connection,
                                        "SELECT COUNT(*) FROM sys_dept WHERE parent_id = ?"
                                                + " AND del_flag = '0'");
                        PreparedStatement members =
                                OrgSql.statement(
                                        connection,
                                        "SELECT COUNT(*) FROM sys_user_profile WHERE dept_id = ?")) {
                    children.setLong(1, id);
                    try (ResultSet result = children.executeQuery()) {
                        result.next();
                        if (result.getLong(1) > 0) {
                            throw new DomainException(ErrorCode.REFERENCES_EXIST);
                        }
                    }
                    members.setLong(1, id);
                    try (ResultSet result = members.executeQuery()) {
                        result.next();
                        if (result.getLong(1) > 0) {
                            throw new DomainException(ErrorCode.REFERENCES_EXIST);
                        }
                    }
                }
                try (PreparedStatement statement =
                        OrgSql.statement(
                                connection,
                                "UPDATE sys_dept SET del_flag = '1', version = version + 1,"
                                        + " update_time = ? WHERE dept_id = ? AND version = ?")) {
                    statement.setObject(1, OrgSql.nowUtc());
                    statement.setLong(2, id);
                    statement.setLong(3, expectedVersion);
                    if (statement.executeUpdate() != 1) {
                        throw new DomainException(ErrorCode.VERSION_CONFLICT);
                    }
                }
                writeOperationLog(connection, actorId, "DEPARTMENT_DELETE", "DEPARTMENT", id, null);
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    // Never mask the in-flight failure with a rollback error.
                }
                throw failure;
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    public List<DepartmentRow> listDepartments() {
        try (Connection connection = OrgSql.configure(primary);
                PreparedStatement statement =
                        OrgSql.statement(
                                connection,
                                "SELECT dept_id, parent_id, dept_name, status, version, ancestors"
                                        + " FROM sys_dept WHERE del_flag = '0' ORDER BY dept_id");
                ResultSet result = statement.executeQuery()) {
            List<DepartmentRow> rows = new ArrayList<>();
            while (result.next()) {
                rows.add(departmentRow(result));
            }
            return rows;
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    public DepartmentRow getDepartment(long id) {
        try (Connection connection = OrgSql.configure(primary);
                PreparedStatement statement =
                        OrgSql.statement(
                                connection,
                                "SELECT dept_id, parent_id, dept_name, status, version, ancestors"
                                        + " FROM sys_dept WHERE dept_id = ? AND del_flag = '0'")) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
                }
                return departmentRow(result);
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    // ---- roles -------------------------------------------------------------

    public RoleRow createRole(
            String key, String name, boolean enabled, List<String> permissionCodes, long actorId) {
        OrgSql.requireText(key, 100);
        OrgSql.requireText(name, 30);
        OrgSql.requireActor(actorId);
        List<String> codes = normalizedCodes(permissionCodes);
        if (!ROLE_KEY.matcher(key).matches()) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        try (Connection connection = OrgSql.configure(primary)) {
            connection.setAutoCommit(false);
            try {
                requireRoleKeyFree(connection, key);
                long id;
                try (PreparedStatement statement =
                        OrgSql.statement(
                                connection,
                                "INSERT INTO sys_role (role_key, role_name, status, del_flag,"
                                        + " create_time, update_time) VALUES (?, ?, ?, '0', ?, ?)",
                                Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, key);
                    statement.setString(2, name);
                    statement.setString(3, enabled ? "0" : "1");
                    statement.setObject(4, OrgSql.nowUtc());
                    statement.setObject(5, OrgSql.nowUtc());
                    statement.executeUpdate();
                    id = generatedKey(statement);
                }
                insertPermissions(connection, id, codes);
                writeOperationLog(
                        connection,
                        actorId,
                        "ROLE_CREATE",
                        "ROLE",
                        id,
                        "{\"key\":\"" + key + "\",\"permissionCount\":" + codes.size() + "}");
                connection.commit();
                return getRole(id);
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    // Never mask the in-flight failure with a rollback error.
                }
                throw failure;
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    public RoleRow updateRole(
            long id,
            String name,
            Boolean enabled,
            List<String> permissionCodes,
            long expectedVersion,
            long actorId) {
        OrgSql.requireActor(actorId);
        requireVersion(expectedVersion);
        List<String> codes = permissionCodes == null ? null : normalizedCodes(permissionCodes);
        try (Connection connection = OrgSql.configure(primary)) {
            connection.setAutoCommit(false);
            try {
                lockRole(connection, id);
                try (PreparedStatement statement =
                        OrgSql.statement(
                                connection,
                                "UPDATE sys_role SET role_name = ?,"
                                        + " status = COALESCE(?, status), version = version + 1,"
                                        + " update_time = ? WHERE role_id = ? AND version = ?"
                                        + " AND del_flag = '0'")) {
                    statement.setString(1, name);
                    if (enabled == null) {
                        statement.setNull(2, java.sql.Types.CHAR);
                    } else {
                        statement.setString(2, enabled ? "0" : "1");
                    }
                    statement.setObject(3, OrgSql.nowUtc());
                    statement.setLong(4, id);
                    statement.setLong(5, expectedVersion);
                    if (statement.executeUpdate() != 1) {
                        throw new DomainException(ErrorCode.VERSION_CONFLICT);
                    }
                }
                if (codes != null) {
                    try (PreparedStatement delete =
                            OrgSql.statement(
                                    connection,
                                    "DELETE FROM sys_role_permission WHERE role_id = ?")) {
                        delete.setLong(1, id);
                        delete.executeUpdate();
                    }
                    insertPermissions(connection, id, codes);
                }
                writeOperationLog(
                        connection,
                        actorId,
                        "ROLE_UPDATE",
                        "ROLE",
                        id,
                        "{\"permissionCount\":" + (codes == null ? 0 : codes.size()) + "}");
                connection.commit();
                return getRole(id);
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    // Never mask the in-flight failure with a rollback error.
                }
                throw failure;
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    public void deleteRole(long id, long expectedVersion, long actorId) {
        OrgSql.requireActor(actorId);
        requireVersion(expectedVersion);
        try (Connection connection = OrgSql.configure(primary)) {
            connection.setAutoCommit(false);
            try {
                lockRole(connection, id);
                try (PreparedStatement members =
                                OrgSql.statement(
                                        connection,
                                        "SELECT COUNT(*) FROM sys_user_role WHERE role_id = ?");
                        PreparedStatement grants =
                                OrgSql.statement(
                                        connection,
                                        "SELECT COUNT(*) FROM acl_grant WHERE subject_type = 'ROLE'"
                                                + " AND subject_id = ? AND status = 'ACTIVE'")) {
                    members.setLong(1, id);
                    try (ResultSet result = members.executeQuery()) {
                        result.next();
                        if (result.getLong(1) > 0) {
                            throw new DomainException(ErrorCode.REFERENCES_EXIST);
                        }
                    }
                    grants.setLong(1, id);
                    try (ResultSet result = grants.executeQuery()) {
                        result.next();
                        if (result.getLong(1) > 0) {
                            throw new DomainException(ErrorCode.REFERENCES_EXIST);
                        }
                    }
                }
                try (PreparedStatement statement =
                        OrgSql.statement(
                                connection,
                                "UPDATE sys_role SET del_flag = '1', version = version + 1,"
                                        + " update_time = ? WHERE role_id = ? AND version = ?")) {
                    statement.setObject(1, OrgSql.nowUtc());
                    statement.setLong(2, id);
                    statement.setLong(3, expectedVersion);
                    if (statement.executeUpdate() != 1) {
                        throw new DomainException(ErrorCode.VERSION_CONFLICT);
                    }
                }
                try (PreparedStatement delete =
                        OrgSql.statement(
                                connection, "DELETE FROM sys_role_permission WHERE role_id = ?")) {
                    delete.setLong(1, id);
                    delete.executeUpdate();
                }
                writeOperationLog(connection, actorId, "ROLE_DELETE", "ROLE", id, null);
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    // Never mask the in-flight failure with a rollback error.
                }
                throw failure;
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    public List<RoleRow> listRoles() {
        try (Connection connection = OrgSql.configure(primary)) {
            Map<Long, RoleRow> rows = new LinkedHashMap<>();
            try (PreparedStatement statement =
                            OrgSql.statement(
                                    connection,
                                    "SELECT role_id, role_key, role_name, status, version FROM sys_role"
                                            + " WHERE del_flag = '0' ORDER BY role_id");
                    ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    long id = result.getLong(1);
                    rows.put(
                            id,
                            new RoleRow(
                                    id,
                                    result.getString(2),
                                    result.getString(3),
                                    "0".equals(result.getString(4)),
                                    new ArrayList<>(),
                                    result.getLong(5)));
                }
            }
            try (PreparedStatement statement =
                            OrgSql.statement(
                                    connection,
                                    "SELECT role_id, permission_code FROM sys_role_permission"
                                            + " ORDER BY role_id, permission_code");
                    ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    RoleRow role = rows.get(result.getLong(1));
                    if (role != null) {
                        role.permissionCodes().add(result.getString(2));
                    }
                }
            }
            return List.copyOf(rows.values());
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    public RoleRow getRole(long id) {
        try (Connection connection = OrgSql.configure(primary);
                PreparedStatement statement =
                        OrgSql.statement(
                                connection,
                                "SELECT role_id, role_key, role_name, status, version FROM sys_role"
                                        + " WHERE role_id = ? AND del_flag = '0'")) {
            statement.setLong(1, id);
            List<String> codes = new ArrayList<>();
            RoleRow role;
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
                }
                role =
                        new RoleRow(
                                result.getLong(1),
                                result.getString(2),
                                result.getString(3),
                                "0".equals(result.getString(4)),
                                codes,
                                result.getLong(5));
            }
            try (PreparedStatement permissions =
                    OrgSql.statement(
                            connection,
                            "SELECT permission_code FROM sys_role_permission WHERE role_id = ?"
                                    + " ORDER BY permission_code")) {
                permissions.setLong(1, id);
                try (ResultSet result = permissions.executeQuery()) {
                    while (result.next()) {
                        codes.add(result.getString(1));
                    }
                }
            }
            return new RoleRow(
                    role.id(),
                    role.key(),
                    role.name(),
                    role.enabled(),
                    List.copyOf(codes),
                    role.version());
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    // ---- users -------------------------------------------------------------

    public UserRow createUser(
            String username,
            String displayName,
            long deptId,
            String passwordHash,
            List<Long> roleIds,
            boolean enabled,
            long actorId) {
        OrgSql.requireText(username, 30);
        OrgSql.requireText(displayName, 64);
        OrgSql.requireText(passwordHash, 100);
        OrgSql.requireActor(actorId);
        String normalized = username.toLowerCase(java.util.Locale.ROOT);
        if (!username.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,29}")) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        List<Long> roles = normalizedIds(roleIds);
        try (Connection connection = OrgSql.configure(primary)) {
            connection.setAutoCommit(false);
            try {
                requireDepartmentExists(connection, deptId);
                requireUserFree(connection, normalized);
                for (long roleId : roles) {
                    requireRoleExists(connection, roleId);
                }
                long userId;
                try (PreparedStatement statement =
                        OrgSql.statement(
                                connection,
                                "INSERT INTO sys_user_login_name (user_id, normalized_name,"
                                        + " create_time, update_time) VALUES (?, ?, ?, ?)")) {
                    userId = nextUserId(connection);
                    statement.setLong(1, userId);
                    statement.setString(2, normalized);
                    statement.setObject(3, OrgSql.nowUtc());
                    statement.setObject(4, OrgSql.nowUtc());
                    statement.executeUpdate();
                }
                try (PreparedStatement statement =
                        OrgSql.statement(
                                connection,
                                "INSERT INTO sys_user_profile (user_id, dept_id, display_name, email,"
                                        + " phone_number, remark, password_hash, status, version,"
                                        + " create_time, update_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1,"
                                        + " ?, ?)")) {
                    statement.setLong(1, userId);
                    statement.setLong(2, deptId);
                    statement.setString(3, displayName);
                    statement.setNull(4, java.sql.Types.VARCHAR);
                    statement.setNull(5, java.sql.Types.VARCHAR);
                    statement.setNull(6, java.sql.Types.VARCHAR);
                    statement.setString(7, passwordHash);
                    statement.setString(8, enabled ? "ACTIVE" : "DISABLED");
                    statement.setObject(9, OrgSql.nowUtc());
                    statement.setObject(10, OrgSql.nowUtc());
                    statement.executeUpdate();
                }
                try (PreparedStatement security =
                        OrgSql.statement(
                                connection,
                                "INSERT INTO sys_user_security (user_id,auth_version,identity_version,identity_state,version,create_time,update_time)"
                                        + " VALUES (?,1,1,'UNBOUND',1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))")) {
                    security.setLong(1, userId);
                    security.executeUpdate();
                }
                insertUserRoles(connection, userId, roles);
                writeOperationLog(
                        connection,
                        actorId,
                        "USER_CREATE",
                        "USER",
                        userId,
                        "{\"username\":\"" + username + "\",\"enabled\":" + enabled + "}");
                connection.commit();
                return getUser(userId);
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    // Never mask the in-flight failure with a rollback error.
                }
                throw failure;
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    /**
     * Writes one admin operations-trail row inside the caller's transaction (PRD: 管理操作可追溯).
     * Payloads are caller-provided and already masked; this helper never formats sensitive data.
     */
    private static void writeOperationLog(
            Connection connection,
            long actorId,
            String action,
            String aggregateType,
            long aggregateId,
            String afterMasked) {
        try (PreparedStatement statement =
                OrgSql.statement(
                        connection,
                        "INSERT INTO admin_operation_log (actor_id, request_id, action,"
                                + " aggregate_type, aggregate_id, before_masked, after_masked,"
                                + " occurred_at, create_time)"
                                + " VALUES (?, ?, ?, ?, ?, NULL, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))")) {
            statement.setLong(1, actorId);
            statement.setString(2, UUID.randomUUID().toString());
            statement.setString(3, action);
            statement.setString(4, aggregateType);
            statement.setLong(5, aggregateId);
            statement.setString(6, afterMasked);
            statement.executeUpdate();
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    public UserRow updateUser(
            long id,
            String displayName,
            long deptId,
            String email,
            String phoneNumber,
            String remark,
            Boolean enabled,
            List<Long> roleIds,
            long expectedVersion,
            long actorId) {
        OrgSql.requireText(displayName, 64);
        OrgSql.requireActor(actorId);
        requireVersion(expectedVersion);
        requireNullableText(email, 50);
        requireNullableText(phoneNumber, 11);
        requireNullableText(remark, 500);
        List<Long> roles = roleIds == null ? null : normalizedIds(roleIds);
        UserRow before = getUser(id);
        if (before.version() != expectedVersion) {
            throw new DomainException(ErrorCode.VERSION_CONFLICT);
        }
        boolean statusChanged = enabled != null && enabled != before.enabled();
        if (statusChanged) {
            UserSecurityStore.State captured = new UserSecurityStore(primary).read(id);
            requireEditableIdentity(captured);
            if (captured.authVersion() == Long.MAX_VALUE
                    || captured.identityVersion() == Long.MAX_VALUE) {
                throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
            }
            long nextAuthVersion = captured.authVersion() + 1;
            AtomicReference<UserRow> updated = new AtomicReference<>();
            new SecurityChangeTransaction(primary)
                    .execute(
                            new SecurityEvent(
                                    UUID.randomUUID(),
                                    SecurityEvent.Kind.USER_SECURITY_CHANGED,
                                    new SecurityEvent.NumericId(id),
                                    nextAuthVersion),
                            connection -> {
                                UserSecurityStore.State current =
                                        UserSecurityStore.read(connection, id, true);
                                requireEditableIdentity(current);
                                if (!captured.equals(current)) {
                                    throw new DomainException(ErrorCode.VERSION_CONFLICT);
                                }
                                UserRow written =
                                        applyUserUpdate(
                                                connection,
                                                before,
                                                displayName,
                                                deptId,
                                                email,
                                                phoneNumber,
                                                remark,
                                                enabled,
                                                roles,
                                                actorId);
                                try (PreparedStatement statement =
                                        OrgSql.statement(
                                                connection,
                                                "UPDATE sys_user_security SET auth_version=auth_version+1,"
                                                        + " identity_version=identity_version+1,version=version+1,"
                                                        + " update_time=UTC_TIMESTAMP(6) WHERE user_id=?"
                                                        + " AND auth_version=? AND identity_version=? AND identity_state=?")) {
                                    statement.setLong(1, id);
                                    statement.setLong(2, captured.authVersion());
                                    statement.setLong(3, captured.identityVersion());
                                    statement.setString(4, captured.identityState());
                                    if (statement.executeUpdate() != 1) {
                                        throw new DomainException(ErrorCode.VERSION_CONFLICT);
                                    }
                                }
                                UserSecurityStore.revokeCredentials(connection, id);
                                updated.set(written);
                            });
            return updated.get();
        }
        try (Connection connection = OrgSql.configure(primary)) {
            connection.setAutoCommit(false);
            try {
                UserSecurityStore.lockGlobal(connection);
                UserSecurityStore.State security = UserSecurityStore.read(connection, id, true);
                requireEditableIdentity(security);
                UserRow updated =
                        applyUserUpdate(
                                connection,
                                before,
                                displayName,
                                deptId,
                                email,
                                phoneNumber,
                                remark,
                                enabled,
                                roles,
                                actorId);
                connection.commit();
                return updated;
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    // Never mask the in-flight failure with a rollback error.
                }
                throw failure;
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private static void requireEditableIdentity(UserSecurityStore.State security) {
        if ("CHANGE_PENDING".equals(security.identityState())) {
            throw new DomainException(ErrorCode.VERSION_CONFLICT);
        }
        if (!security.ready() && !"UNBOUND".equals(security.identityState())) {
            throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
        }
    }

    private UserRow applyUserUpdate(
            Connection connection,
            UserRow before,
            String displayName,
            long deptId,
            String email,
            String phoneNumber,
            String remark,
            Boolean enabled,
            List<Long> roles,
            long actorId)
            throws SQLException {
        requireDepartmentExists(connection, deptId);
        try (PreparedStatement statement =
                OrgSql.statement(
                        connection,
                        "UPDATE sys_user_profile SET display_name = ?, dept_id = ?, email = ?,"
                                + " phone_number = ?, remark = ?, status = COALESCE(?, status),"
                                + " version = version + 1, update_time = ? WHERE user_id = ? AND version = ?")) {
            statement.setString(1, displayName);
            statement.setLong(2, deptId);
            setNullableText(statement, 3, email, 50);
            setNullableText(statement, 4, phoneNumber, 11);
            setNullableText(statement, 5, remark, 500);
            if (enabled == null) {
                statement.setNull(6, java.sql.Types.VARCHAR);
            } else {
                statement.setString(6, enabled ? "ACTIVE" : "DISABLED");
            }
            statement.setObject(7, OrgSql.nowUtc());
            statement.setLong(8, before.id());
            statement.setLong(9, before.version());
            if (statement.executeUpdate() != 1) {
                throw conflictOrMissingUser(connection, before.id());
            }
        }
        if (roles != null) {
            for (long roleId : roles) {
                requireRoleExists(connection, roleId);
            }
            try (PreparedStatement delete =
                    OrgSql.statement(connection, "DELETE FROM sys_user_role WHERE user_id = ?")) {
                delete.setLong(1, before.id());
                delete.executeUpdate();
            }
            insertUserRoles(connection, before.id(), roles);
        }
        writeOperationLog(
                connection,
                actorId,
                "USER_UPDATE",
                "USER",
                before.id(),
                "{\"displayName\":\""
                        + displayName
                        + "\",\"deptId\":"
                        + deptId
                        + ",\"enabled\":"
                        + enabled
                        + ",\"rolesChanged\":"
                        + (roles != null)
                        + "}");
        return readUser(connection, before.id());
    }

    public List<UserRow> listUsers() {
        try (Connection connection = OrgSql.configure(primary)) {
            Map<Long, UserRow> rows = new LinkedHashMap<>();
            try (PreparedStatement statement =
                            OrgSql.statement(
                                    connection,
                                    "SELECT p.user_id, l.normalized_name, p.display_name, p.dept_id, p.email,"
                                            + " p.phone_number, p.remark, p.status, p.version"
                                            + " FROM sys_user_profile p"
                                            + " JOIN sys_user_login_name l ON l.user_id = p.user_id"
                                            + " ORDER BY p.user_id");
                    ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    long id = result.getLong(1);
                    rows.put(
                            id,
                            new UserRow(
                                    id,
                                    result.getString(2),
                                    result.getString(3),
                                    result.getLong(4),
                                    result.getString(5),
                                    result.getString(6),
                                    result.getString(7),
                                    "ACTIVE".equals(result.getString(8)),
                                    new ArrayList<>(),
                                    result.getLong(9)));
                }
            }
            appendUserRoles(connection, rows);
            return List.copyOf(rows.values());
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    public UserRow getUser(long id) {
        try (Connection connection = OrgSql.configure(primary)) {
            return readUser(connection, id);
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private UserRow readUser(Connection connection, long id) throws SQLException {
        UserRow row;
        try (PreparedStatement statement =
                OrgSql.statement(
                        connection,
                        "SELECT p.user_id, l.normalized_name, p.display_name, p.dept_id, p.email,"
                                + " p.phone_number, p.remark, p.status, p.version"
                                + " FROM sys_user_profile p"
                                + " JOIN sys_user_login_name l ON l.user_id = p.user_id"
                                + " WHERE p.user_id = ?")) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
                }
                row =
                        new UserRow(
                                result.getLong(1),
                                result.getString(2),
                                result.getString(3),
                                result.getLong(4),
                                result.getString(5),
                                result.getString(6),
                                result.getString(7),
                                "ACTIVE".equals(result.getString(8)),
                                new ArrayList<>(),
                                result.getLong(9));
            }
        }
        Map<Long, UserRow> rows = new LinkedHashMap<>();
        rows.put(id, row);
        appendUserRoles(connection, rows);
        return row;
    }

    // ---- internal ----------------------------------------------------------

    private DepartmentRow lockDepartment(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement =
                OrgSql.statement(
                        connection,
                        "SELECT dept_id, parent_id, dept_name, status, version, ancestors"
                                + " FROM sys_dept WHERE dept_id = ? AND del_flag = '0' FOR UPDATE")) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
                }
                return departmentRow(result);
            }
        }
    }

    private void lockRole(Connection connection, long id) throws SQLException {
        try (PreparedStatement statement =
                OrgSql.statement(
                        connection,
                        "SELECT role_id FROM sys_role WHERE role_id = ? AND del_flag = '0'"
                                + " FOR UPDATE")) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
                }
            }
        }
    }

    private static DepartmentRow departmentRow(ResultSet result) throws SQLException {
        long parentId = result.getLong(2);
        return new DepartmentRow(
                result.getLong(1),
                parentId == 0 ? null : parentId,
                result.getString(3),
                "0".equals(result.getString(4)),
                result.getLong(5),
                result.getString(6));
    }

    private static DomainException conflictOrMissing(Connection connection, long id)
            throws SQLException {
        try (PreparedStatement statement =
                OrgSql.statement(
                        connection,
                        "SELECT 1 FROM sys_dept WHERE dept_id = ? AND del_flag = '0'")) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return new DomainException(
                        result.next() ? ErrorCode.VERSION_CONFLICT : ErrorCode.RESOURCE_NOT_FOUND);
            }
        }
    }

    private static DomainException conflictOrMissingUser(Connection connection, long id)
            throws SQLException {
        try (PreparedStatement statement =
                OrgSql.statement(connection, "SELECT 1 FROM sys_user_profile WHERE user_id = ?")) {
            statement.setLong(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return new DomainException(
                        result.next() ? ErrorCode.VERSION_CONFLICT : ErrorCode.RESOURCE_NOT_FOUND);
            }
        }
    }

    private static void requireRoleKeyFree(Connection connection, String key) throws SQLException {
        try (PreparedStatement statement =
                OrgSql.statement(
                        connection,
                        "SELECT 1 FROM sys_role WHERE role_key = ? AND del_flag = '0'")) {
            statement.setString(1, key);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    throw new DomainException(ErrorCode.INVALID_ARGUMENT);
                }
            }
        }
    }

    private static void requireRoleExists(Connection connection, long roleId) throws SQLException {
        try (PreparedStatement statement =
                OrgSql.statement(
                        connection,
                        "SELECT 1 FROM sys_role WHERE role_id = ? AND del_flag = '0'")) {
            statement.setLong(1, roleId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
                }
            }
        }
    }

    private static void requireDepartmentExists(Connection connection, long deptId)
            throws SQLException {
        try (PreparedStatement statement =
                OrgSql.statement(
                        connection,
                        "SELECT 1 FROM sys_dept WHERE dept_id = ? AND del_flag = '0'")) {
            statement.setLong(1, deptId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
                }
            }
        }
    }

    private static void requireUserFree(Connection connection, String normalized)
            throws SQLException {
        try (PreparedStatement statement =
                OrgSql.statement(
                        connection,
                        "SELECT 1 FROM sys_user_login_name WHERE normalized_name = ?")) {
            statement.setString(1, normalized);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    throw new DomainException(ErrorCode.INVALID_ARGUMENT);
                }
            }
        }
    }

    private static long nextUserId(Connection connection) throws SQLException {
        try (PreparedStatement statement =
                OrgSql.statement(
                        connection,
                        "SELECT COALESCE(MAX(user_id), 0) + 1 FROM sys_user_login_name"
                                + " UNION ALL SELECT COALESCE(MAX(user_id), 0) + 1"
                                + " FROM sys_user_profile LIMIT 1")) {
            statement.setMaxRows(1);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static void insertPermissions(Connection connection, long roleId, List<String> codes)
            throws SQLException {
        try (PreparedStatement statement =
                OrgSql.statement(
                        connection,
                        "INSERT INTO sys_role_permission (role_id, permission_code, create_time,"
                                + " update_time) VALUES (?, ?, ?, ?)")) {
            for (String code : codes) {
                statement.setLong(1, roleId);
                statement.setString(2, code);
                statement.setObject(3, OrgSql.nowUtc());
                statement.setObject(4, OrgSql.nowUtc());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void insertUserRoles(Connection connection, long userId, List<Long> roleIds)
            throws SQLException {
        try (PreparedStatement statement =
                OrgSql.statement(
                        connection,
                        "INSERT INTO sys_user_role (user_id, role_id, create_time, update_time)"
                                + " VALUES (?, ?, ?, ?)")) {
            for (long roleId : roleIds) {
                statement.setLong(1, userId);
                statement.setLong(2, roleId);
                statement.setObject(3, OrgSql.nowUtc());
                statement.setObject(4, OrgSql.nowUtc());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void appendUserRoles(Connection connection, Map<Long, UserRow> rows)
            throws SQLException {
        try (PreparedStatement statement =
                        OrgSql.statement(
                                connection,
                                "SELECT user_id, role_id FROM sys_user_role ORDER BY user_id, role_id");
                ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                UserRow row = rows.get(result.getLong(1));
                if (row != null) {
                    row.roleIds().add(result.getLong(2));
                }
            }
        }
    }

    private static Set<Long> ancestorChain(String ancestors, long selfId) {
        Set<Long> chain = new HashSet<>();
        if (ancestors != null && !ancestors.isEmpty()) {
            Arrays.stream(ancestors.split(","))
                    .filter(token -> !token.isBlank() && !"0".equals(token))
                    .map(Long::parseLong)
                    .forEach(chain::add);
        }
        return chain;
    }

    private static String rebuildChain(String chain, long movedId, String newPrefix) {
        List<String> tokens = new ArrayList<>(Arrays.asList(chain.split(",")));
        int position = tokens.indexOf(String.valueOf(movedId));
        List<String> tail = position >= 0 ? tokens.subList(position + 1, tokens.size()) : List.of();
        String rebuilt = newPrefix;
        for (String token : tail) {
            rebuilt = rebuilt.isEmpty() ? token : rebuilt + "," + token;
        }
        return rebuilt;
    }

    private static List<String> normalizedCodes(List<String> codes) {
        if (codes == null) {
            return List.of();
        }
        Set<String> unique = new HashSet<>();
        for (String code : codes) {
            OrgSql.requireText(code, 100);
            if (!unique.add(code)) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
        }
        return List.copyOf(codes);
    }

    private static List<Long> normalizedIds(List<Long> ids) {
        if (ids == null) {
            return List.of();
        }
        Set<Long> unique = new HashSet<>();
        for (Long id : ids) {
            if (id == null || id <= 0 || !unique.add(id)) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
        }
        return List.copyOf(ids);
    }

    private static void requireVersion(long expectedVersion) {
        if (expectedVersion < 1) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
    }

    private static void requireNullableText(String value, int maxLength) {
        if (value != null && value.length() > maxLength) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
    }

    private static void setNullableText(
            PreparedStatement statement, int parameterIndex, String value, int maxLength)
            throws SQLException {
        if (value != null && value.length() > maxLength) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (value == null || value.isBlank()) {
            statement.setNull(parameterIndex, java.sql.Types.VARCHAR);
        } else {
            statement.setString(parameterIndex, value);
        }
    }

    private static long generatedKey(Statement statement) throws SQLException {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            if (!keys.next()) {
                throw new DomainException(ErrorCode.INTERNAL_ERROR);
            }
            return keys.getLong(1);
        }
    }
}
