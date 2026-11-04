package local.ptmcp.persistence.security;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.SecurityChangeTransaction;
import local.ptmcp.persistence.SecurityEvent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

import javax.sql.DataSource;

/**
 * Administrator-issued first credential for an UNBOUND metadata identity. The immutable intent is
 * committed before the host row exists, so a failed or unknown host write can be retried without
 * accepting a new password or adopting a pre-existing host account.
 */
public final class IdentityProvisions {
    private static final String SELECT_OPERATION =
            "SELECT o.operation_id,o.auth_version,o.profile_version,o.target_hash,o.status,"
                    + "s.auth_version,s.identity_state FROM identity_provision o"
                    + " JOIN sys_user_security s ON s.user_id=o.user_id";
    private final DataSource primary;

    public IdentityProvisions(DataSource primary) {
        this.primary = Objects.requireNonNull(primary, "primary");
    }

    public record Pending(
            UUID id,
            long userId,
            long authVersion,
            long profileVersion,
            String target,
            boolean completed) {
        @Override
        public String toString() {
            return "IdentityProvision[redacted]";
        }
    }

    public Pending begin(long userId, long expectedProfileVersion, long actorId, String target) {
        if (userId <= 0
                || actorId <= 0
                || expectedProfileVersion <= 0
                || expectedProfileVersion == Long.MAX_VALUE
                || target == null
                || target.isBlank()
                || target.length() > 100) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        UUID operation = UUID.randomUUID();
        return new SecurityChangeTransaction(primary)
                .executeReturning(
                        connection -> {
                            UserSecurityStore.State state =
                                    UserSecurityStore.read(connection, userId, true);
                            if (!"UNBOUND".equals(state.identityState()))
                                throw new DomainException(ErrorCode.VERSION_CONFLICT);
                            if (state.authVersion() == Long.MAX_VALUE
                                    || state.identityVersion() == Long.MAX_VALUE)
                                throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
                            try (PreparedStatement existing =
                                    statement(
                                            connection,
                                            "SELECT status FROM identity_provision"
                                                    + " WHERE user_id=? FOR UPDATE")) {
                                existing.setLong(1, userId);
                                try (ResultSet result = existing.executeQuery()) {
                                    if (result.next())
                                        throw new DomainException(ErrorCode.OPERATION_IN_PROGRESS);
                                }
                            }
                            UserSecurityStore.requireActiveProfile(connection, userId);
                            long authVersion = state.authVersion() + 1;
                            long profileVersion = expectedProfileVersion + 1;
                            try (PreparedStatement update =
                                    statement(
                                            connection,
                                            "UPDATE sys_user_profile SET password_hash='external',"
                                                    + "version=?,update_time=UTC_TIMESTAMP(6)"
                                                    + " WHERE user_id=? AND version=?")) {
                                update.setLong(1, profileVersion);
                                update.setLong(2, userId);
                                update.setLong(3, expectedProfileVersion);
                                if (update.executeUpdate() != 1)
                                    throw new DomainException(ErrorCode.VERSION_CONFLICT);
                            }
                            try (PreparedStatement update =
                                    statement(
                                            connection,
                                            "UPDATE sys_user_security SET auth_version=?,"
                                                    + "identity_version=identity_version+1,"
                                                    + "version=version+1,"
                                                    + "update_time=UTC_TIMESTAMP(6)"
                                                    + " WHERE user_id=? AND identity_state='UNBOUND'")) {
                                update.setLong(1, authVersion);
                                update.setLong(2, userId);
                                if (update.executeUpdate() != 1)
                                    throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
                            }
                            try (PreparedStatement insert =
                                    statement(
                                            connection,
                                            "INSERT INTO identity_provision"
                                                    + " (operation_id,user_id,auth_version,"
                                                    + "profile_version,target_hash,status,"
                                                    + "requested_by,create_time,update_time)"
                                                    + " VALUES (?,?,?,?,?,'PENDING',?,"
                                                    + "UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))")) {
                                insert.setString(1, operation.toString());
                                insert.setLong(2, userId);
                                insert.setLong(3, authVersion);
                                insert.setLong(4, profileVersion);
                                insert.setString(5, target);
                                insert.setLong(6, actorId);
                                if (insert.executeUpdate() != 1)
                                    throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
                            }
                            try (PreparedStatement insert =
                                    statement(
                                            connection,
                                            "INSERT INTO admin_operation_log"
                                                    + " (actor_id,request_id,action,aggregate_type,"
                                                    + "aggregate_id,before_masked,after_masked,"
                                                    + "occurred_at,create_time)"
                                                    + " VALUES (?,?,'USER_CREDENTIAL_ISSUED','USER',?"
                                                    + ",'{\"identityState\":\"UNBOUND\"}'"
                                                    + ",'{\"identityState\":\"PENDING\"}'"
                                                    + ",UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))")) {
                                insert.setLong(1, actorId);
                                insert.setString(2, operation.toString());
                                insert.setLong(3, userId);
                                if (insert.executeUpdate() != 1)
                                    throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
                            }
                            return new SecurityChangeTransaction.Outcome<>(
                                    new Pending(
                                            operation,
                                            userId,
                                            authVersion,
                                            profileVersion,
                                            target,
                                            false),
                                    new SecurityEvent(
                                            operation,
                                            SecurityEvent.Kind.USER_SECURITY_CHANGED,
                                            new SecurityEvent.NumericId(userId),
                                            authVersion));
                        })
                .value();
    }

    /** True while a committed intent still owns this identity; the caller must use retry. */
    public boolean pending(long userId) {
        if (userId <= 0) throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        try (Connection connection = primary.getConnection();
                PreparedStatement query =
                        statement(
                                connection,
                                "SELECT 1 FROM identity_provision"
                                        + " WHERE user_id=? AND status='PENDING'")) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            query.setLong(1, userId);
            try (ResultSet result = query.executeQuery()) {
                return result.next();
            }
        } catch (SQLException failure) {
            throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
        }
    }

    /** Reads the persisted intent by the aggregate version the caller already observed. */
    public Pending findIfPresent(long userId, long profileVersion) {
        if (userId <= 0 || profileVersion <= 0)
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        try (Connection connection = primary.getConnection();
                PreparedStatement query =
                        statement(
                                connection,
                                SELECT_OPERATION + " WHERE o.user_id=? AND o.profile_version=?")) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            query.setLong(1, userId);
            query.setLong(2, profileVersion);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) return null;
                Pending operation =
                        new Pending(
                                UUID.fromString(result.getString(1)),
                                userId,
                                result.getLong(2),
                                result.getLong(3),
                                result.getString(4),
                                "COMPLETED".equals(result.getString(5)));
                boolean bound =
                        operation.authVersion() == result.getLong(6)
                                && (operation.completed()
                                        ? "READY".equals(result.getString(7))
                                        : "UNBOUND".equals(result.getString(7)));
                if (!bound) throw new DomainException(ErrorCode.VERSION_CONFLICT);
                return operation;
            }
        } catch (SQLException failure) {
            throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
        }
    }

    public long complete(Pending operation) {
        if (operation == null || operation.completed())
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        try {
            new SecurityChangeTransaction(primary)
                    .execute(
                            new SecurityEvent(
                                    UUID.randomUUID(),
                                    SecurityEvent.Kind.USER_SECURITY_CHANGED,
                                    new SecurityEvent.NumericId(operation.userId()),
                                    operation.authVersion()),
                            connection -> {
                                UserSecurityStore.State state =
                                        UserSecurityStore.read(
                                                connection, operation.userId(), true);
                                String status;
                                try (PreparedStatement query =
                                        statement(
                                                connection,
                                                "SELECT status FROM identity_provision"
                                                        + " WHERE operation_id=? AND user_id=?"
                                                        + " AND auth_version=?"
                                                        + " AND profile_version=? FOR UPDATE")) {
                                    query.setString(1, operation.id().toString());
                                    query.setLong(2, operation.userId());
                                    query.setLong(3, operation.authVersion());
                                    query.setLong(4, operation.profileVersion());
                                    try (ResultSet result = query.executeQuery()) {
                                        if (!result.next())
                                            throw new DomainException(ErrorCode.VERSION_CONFLICT);
                                        status = result.getString(1);
                                    }
                                }
                                if ("COMPLETED".equals(status)) {
                                    // A retried or concurrent completion must roll back, not add a
                                    // second epoch/event; the caller re-reads the completed state.
                                    throw new DomainException(ErrorCode.VERSION_CONFLICT);
                                }
                                if (!"PENDING".equals(status)
                                        || !"UNBOUND".equals(state.identityState())
                                        || state.authVersion() != operation.authVersion())
                                    throw new DomainException(ErrorCode.VERSION_CONFLICT);
                                UserSecurityStore.requireActiveProfile(
                                        connection, operation.userId());
                                try (PreparedStatement update =
                                        statement(
                                                connection,
                                                "UPDATE sys_user_security SET"
                                                        + " identity_state='READY',"
                                                        + "identity_change_id=NULL,"
                                                        + "password_changed_at=UTC_TIMESTAMP(6),"
                                                        + "version=version+1,"
                                                        + "update_time=UTC_TIMESTAMP(6)"
                                                        + " WHERE user_id=? AND auth_version=?"
                                                        + " AND identity_state='UNBOUND'")) {
                                    update.setLong(1, operation.userId());
                                    update.setLong(2, operation.authVersion());
                                    if (update.executeUpdate() != 1)
                                        throw new DomainException(ErrorCode.VERSION_CONFLICT);
                                }
                                try (PreparedStatement update =
                                        statement(
                                                connection,
                                                "UPDATE identity_provision SET"
                                                        + " status='COMPLETED',target_hash=NULL,"
                                                        + "update_time=UTC_TIMESTAMP(6)"
                                                        + " WHERE operation_id=? AND status='PENDING'")) {
                                    update.setString(1, operation.id().toString());
                                    if (update.executeUpdate() != 1)
                                        throw new DomainException(ErrorCode.VERSION_CONFLICT);
                                }
                            });
        } catch (DomainException conflict) {
            if (conflict.errorCode() != ErrorCode.VERSION_CONFLICT) throw conflict;
            Pending completed = findIfPresent(operation.userId(), operation.profileVersion());
            if (completed == null
                    || !completed.completed()
                    || !completed.id().equals(operation.id())
                    || completed.authVersion() != operation.authVersion()) throw conflict;
        }
        return operation.profileVersion();
    }

    private static PreparedStatement statement(Connection connection, String sql)
            throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        statement.setQueryTimeout(5);
        return statement;
    }
}
