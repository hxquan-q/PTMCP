package local.ptmcp.admin.datasource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Target changes require a fresh namespace generation and explicit reapproval of all authority. */
final class DatasourceTargetIsolation {
    private DatasourceTargetIsolation() {}

    // Each statement owns the fixed alias 'a'. Only numeric values are bound; SQL never uses a
    // request-supplied table/column/identifier. Includes datasource-level descendant authority.
    private static final String TARGET =
            "((a.resource_type = 'DATASOURCE' AND a.resource_id = ?) OR "
                    + "(a.resource_type = 'NAMESPACE' AND a.resource_id IN "
                    + "(SELECT id FROM resource_namespace WHERE datasource_id = ?)) OR "
                    + "(a.resource_type = 'OBJECT' AND a.resource_id IN "
                    + "(SELECT ro.id FROM resource_object ro JOIN resource_namespace ns ON ns.id = ro.namespace_id "
                    + "WHERE ns.datasource_id = ?)))";

    /** Called only after the security-global row and datasource row have been locked. */
    static void retire(Connection connection, long datasourceId, long actorId) throws SQLException {
        try (PreparedStatement statement =
                AdminSql.statement(
                        connection,
                        "UPDATE platform_registry platform JOIN (SELECT DISTINCT a.platform_id FROM platform_scope a WHERE "
                                + TARGET
                                + ") affected ON affected.platform_id = platform.id "
                                + "SET platform.scope_version = platform.scope_version + 1, "
                                + "platform.security_version = platform.security_version + 1, "
                                + "platform.update_time = UTC_TIMESTAMP(6), platform.updated_by = ?")) {
            bindSource(statement, 1, datasourceId);
            statement.setLong(4, actorId);
            statement.executeUpdate();
        }
        try (PreparedStatement statement =
                AdminSql.statement(connection, "DELETE a FROM platform_scope a WHERE " + TARGET)) {
            bindSource(statement, 1, datasourceId);
            statement.executeUpdate();
        }
        try (PreparedStatement statement =
                AdminSql.statement(
                        connection,
                        "UPDATE acl_grant a SET a.status = 'REVOKED', a.reason = 'TARGET_CHANGED', a.version = a.version + 1, "
                                + "a.update_time = UTC_TIMESTAMP(6), a.updated_by = ? WHERE a.status = 'ACTIVE' AND "
                                + TARGET)) {
            statement.setLong(1, actorId);
            bindSource(statement, 2, datasourceId);
            statement.executeUpdate();
        }
        try (PreparedStatement statement =
                AdminSql.statement(
                        connection,
                        "UPDATE admin_delegation a SET a.status = 'REVOKED', a.version = a.version + 1, "
                                + "a.update_time = UTC_TIMESTAMP(6), a.updated_by = ? WHERE a.status = 'ACTIVE' AND "
                                + TARGET)) {
            statement.setLong(1, actorId);
            bindSource(statement, 2, datasourceId);
            statement.executeUpdate();
        }
        try (PreparedStatement statement =
                AdminSql.statement(
                        connection,
                        "UPDATE resource_object ro JOIN resource_namespace ns ON ns.id = ro.namespace_id "
                                + "SET ro.status = 'RETIRED', ro.update_time = UTC_TIMESTAMP(6) "
                                + "WHERE ns.datasource_id = ? AND ro.status IN ('ACTIVE','STAGING')")) {
            statement.setLong(1, datasourceId);
            statement.executeUpdate();
        }
        try (PreparedStatement statement =
                AdminSql.statement(
                        connection,
                        "UPDATE resource_namespace SET status = 'RETIRED', update_time = UTC_TIMESTAMP(6) "
                                + "WHERE datasource_id = ? AND status IN ('ACTIVE','STAGING')")) {
            statement.setLong(1, datasourceId);
            statement.executeUpdate();
        }
        try (PreparedStatement statement =
                AdminSql.statement(
                        connection,
                        "UPDATE schema_snapshot SET status = CASE WHEN status = 'STAGING' THEN 'FAILED' ELSE 'RETIRED' END, "
                                + "update_time = UTC_TIMESTAMP(6) WHERE datasource_id = ? AND status IN ('PUBLISHED','STAGING')")) {
            statement.setLong(1, datasourceId);
            statement.executeUpdate();
        }
    }

    private static void bindSource(PreparedStatement statement, int offset, long sourceId)
            throws SQLException {
        statement.setLong(offset, sourceId);
        statement.setLong(offset + 1, sourceId);
        statement.setLong(offset + 2, sourceId);
    }
}
