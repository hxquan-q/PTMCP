package local.ptmcp.persistence.security;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

import javax.sql.DataSource;

/** Primary-only authority for user credential versions; never infers state from a credential. */
public final class UserSecurityStore {
    private final DataSource primary;

    public UserSecurityStore(DataSource primary) {
        this.primary = Objects.requireNonNull(primary, "primary");
    }

    public record State(long userId, long authVersion, long identityVersion, String identityState) {
        public boolean ready() {
            return "READY".equals(identityState);
        }
    }

    public State read(long userId) {
        try (Connection connection = primary.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            return read(connection, userId, false);
        } catch (SQLException failure) {
            throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
        }
    }

    /** Caller owns the transaction; a locking read requires the global security lock first. */
    public static State read(Connection connection, long userId, boolean lock) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT auth_version, identity_version, identity_state FROM sys_user_security"
                        + " WHERE user_id = ?" + (lock ? " FOR UPDATE" : ""))) {
            statement.setQueryTimeout(5);
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
                }
                State state = new State(userId, result.getLong(1), result.getLong(2), result.getString(3));
                if (state.authVersion() <= 0 || state.identityVersion() <= 0) {
                    throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
                }
                return state;
            }
        }
    }

    public static void requireReady(State state, long expectedAuthVersion) {
        if (!state.ready() || state.authVersion() != expectedAuthVersion) {
            throw new DomainException(ErrorCode.TOKEN_REVOKED);
        }
    }

    public static void lockGlobal(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT policy_epoch FROM sys_security_state WHERE singleton_id = 1 FOR UPDATE")) {
            statement.setQueryTimeout(5);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getLong(1) <= 0) {
                    throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
                }
            }
        }
    }

    public static void requireActiveProfile(Connection connection, long userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT status FROM sys_user_profile WHERE user_id = ?")) {
            statement.setQueryTimeout(5);
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !"ACTIVE".equals(result.getString(1))) {
                    throw new DomainException(ErrorCode.TOKEN_REVOKED);
                }
            }
        }
    }
    public static void revokeCredentials(Connection connection,long userId) throws SQLException {
        for (String sql : new String[] {
                "UPDATE web_session_index SET revoked_at=UTC_TIMESTAMP(6),version=version+1,update_time=UTC_TIMESTAMP(6) WHERE user_id=? AND revoked_at IS NULL",
                "UPDATE personal_token SET status='REVOKED',revoked_at=UTC_TIMESTAMP(6),version=version+1,update_time=UTC_TIMESTAMP(6) WHERE user_id=? AND status='ACTIVE'",
                "UPDATE oauth_refresh_family SET status='REVOKED',revoked_at=UTC_TIMESTAMP(6),revoke_reason='USER_SECURITY_CHANGED',version=version+1,update_time=UTC_TIMESTAMP(6) WHERE user_id=? AND status='ACTIVE'",
                "UPDATE oauth2_authorization SET status='REVOKED',version=version+1,update_time=UTC_TIMESTAMP(6) WHERE user_id=? AND status='ACTIVE'"}) {
            try (PreparedStatement statement=connection.prepareStatement(sql)) {
                statement.setQueryTimeout(5);statement.setLong(1,userId);statement.executeUpdate();
            }
        }
    }

}
