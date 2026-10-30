package local.ptmcp.admin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Independent HTTP contract and real role fixtures; never derives expectations from annotations.
 */
final class AdminActionTestSupport {
    static final Map<String, String> ACTION_BY_ROUTE =
            """
            GET /users ptmcp:user:list
            POST /users ptmcp:user:create
            GET /users/{id} ptmcp:user:read
            PUT /users/{id} ptmcp:user:update
            PUT /users/{id}/status ptmcp:user:status
            PUT /users/{id}/roles ptmcp:user:assign-role
            PUT /users/{id}/department ptmcp:user:move
            GET /users/{id}/identity ptmcp:user:read
            PUT /users/{id}/password ptmcp:user:reset-password
            POST /users/{id}/password/retry ptmcp:user:reset-password
            GET /departments ptmcp:department:list
            POST /departments ptmcp:department:create
            GET /departments/{id} ptmcp:department:read
            PUT /departments/{id} ptmcp:department:update
            DELETE /departments/{id} ptmcp:department:delete
            GET /roles ptmcp:role:list
            POST /roles ptmcp:role:create
            GET /roles/{id} ptmcp:role:read
            PUT /roles/{id} ptmcp:role:update
            DELETE /roles/{id} ptmcp:role:delete
            GET /platforms ptmcp:platform:list
            POST /platforms ptmcp:platform:create
            GET /platforms/{id} ptmcp:platform:read
            PUT /platforms/{id} ptmcp:platform:update
            DELETE /platforms/{id} ptmcp:platform:delete
            GET /platforms/{id}/scopes ptmcp:platform:read
            PUT /platforms/{id}/scopes ptmcp:platform:scope
            GET /platforms/{id}/clients ptmcp:client:list
            POST /platforms/{id}/clients ptmcp:client:create
            GET /clients/{id} ptmcp:client:read
            PUT /clients/{id} ptmcp:client:update
            DELETE /clients/{id} ptmcp:client:delete
            GET /datasources ptmcp:datasource:list
            POST /datasources ptmcp:datasource:create
            GET /datasources/{id} ptmcp:datasource:read
            PUT /datasources/{id} ptmcp:datasource:update
            DELETE /datasources/{id} ptmcp:datasource:delete
            POST /datasources/{id}/test ptmcp:datasource:test
            POST /datasources/{id}/sync ptmcp:datasource:sync
            POST /datasources/{id}/candidates/{candidateId}/publish ptmcp:datasource:update
            GET /jobs/{id} ptmcp:job:read
            GET /metadata/objects ptmcp:metadata:list
            GET /metadata/objects/{id} ptmcp:metadata:read
            PUT /metadata/objects/{id}/dictionary ptmcp:metadata:dictionary
            GET /acl/grants ptmcp:acl:list
            POST /acl/grants ptmcp:acl:create
            GET /acl/grants/{id} ptmcp:acl:read
            PUT /acl/grants/{id} ptmcp:acl:update
            DELETE /acl/grants/{id} ptmcp:acl:delete
            POST /acl/preview ptmcp:acl:preview
            GET /acl/effective ptmcp:acl:effective
            GET /delegations ptmcp:delegation:list
            POST /delegations ptmcp:delegation:create
            DELETE /delegations/{id} ptmcp:delegation:delete
            GET /operations ptmcp:audit:list
            GET /audit ptmcp:audit:list
            GET /audit/{requestId} ptmcp:audit:read
            GET /audit/{requestId}/sensitive-access ptmcp:audit:read
            POST /audit/{requestId}/decrypt ptmcp:audit:decrypt
            """
                    .lines()
                    .map(line -> line.split(" "))
                    .collect(
                            Collectors.toUnmodifiableMap(
                                    parts -> parts[0] + " /api/v1/admin" + parts[1],
                                    parts -> parts[2]));

    private AdminActionTestSupport() {}

    static List<String> standardActions() {
        return Stream.concat(ACTION_BY_ROUTE.values().stream(), Stream.of("ptmcp:platform:scope"))
                .filter(code -> !code.equals("ptmcp:audit:decrypt"))
                .distinct()
                .toList();
    }

    static long grant(Connection connection, long userId, String... codes) throws SQLException {
        long roleId;
        try (PreparedStatement role =
                connection.prepareStatement(
                        "INSERT INTO sys_role (role_key,role_name,status,del_flag,create_time,update_time)"
                                + " VALUES (?,'测试动作角色','0','0',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                        Statement.RETURN_GENERATED_KEYS)) {
            role.setString(1, "test_actions_" + UUID.randomUUID().toString().replace("-", ""));
            role.executeUpdate();
            try (ResultSet keys = role.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("test role was not created");
                }
                roleId = keys.getLong(1);
            }
        }
        try (PreparedStatement delete =
                        connection.prepareStatement("DELETE FROM sys_user_role WHERE user_id=?");
                PreparedStatement member =
                        connection.prepareStatement(
                                "INSERT INTO sys_user_role (user_id,role_id,create_time,update_time)"
                                        + " VALUES (?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))");
                PreparedStatement permission =
                        connection.prepareStatement(
                                "INSERT INTO sys_role_permission (role_id,permission_code,create_time,update_time)"
                                        + " VALUES (?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))")) {
            delete.setLong(1, userId);
            delete.executeUpdate();
            member.setLong(1, userId);
            member.setLong(2, roleId);
            member.executeUpdate();
            for (String code : Arrays.stream(codes).distinct().toList()) {
                permission.setLong(1, roleId);
                permission.setString(2, code);
                permission.addBatch();
            }
            permission.executeBatch();
        }
        return roleId;
    }
}
