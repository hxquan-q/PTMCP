package local.ptmcp.admin.identity;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

import javax.sql.DataSource;

/**
 * Writes a new password hash into the host identity store ({@code sys_user}, owned by the
 * deployment's identity database — never migrated by ptmcp). Called only after the metadata-side
 * security transaction has committed, so a failure here fails closed: credentials are already
 * revoked and the operator simply retries. The plaintext never enters a log, a parameter or this
 * store beyond the live update statement.
 */
public final class IdentityPasswordWriter {
    private final DataSource identity;

    public IdentityPasswordWriter(DataSource identity) {
        this.identity = Objects.requireNonNull(identity, "identity");
    }

    /**
     * @return true when the row was updated; false when the account is missing or disabled (the
     *     console state stays authoritative)
     */
    public boolean updatePasswordHash(long userId, String bcryptHash) {
        if (userId <= 0 || bcryptHash == null || bcryptHash.isBlank()) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        try (Connection connection = identity.getConnection()) {
            connection.setAutoCommit(false);
            try {
                int updated;
                try (PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE sys_user SET password = ?"
                                        + " WHERE user_id = ? AND status = '0' AND del_flag = '0'")) {
                    statement.setString(1, bcryptHash);
                    statement.setLong(2, userId);
                    updated = statement.executeUpdate();
                }
                connection.commit();
                return updated == 1;
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException ignored) {
                    // Never mask the in-flight failure with a rollback error.
                }
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    /** Cheap liveness probe for the identity store (used to decide 503 vs 404 semantics). */
    public boolean accountExists(long userId) {
        try (Connection connection = identity.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT 1 FROM sys_user WHERE user_id = ? AND status = '0'"
                                        + " AND del_flag = '0'")) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }
}
