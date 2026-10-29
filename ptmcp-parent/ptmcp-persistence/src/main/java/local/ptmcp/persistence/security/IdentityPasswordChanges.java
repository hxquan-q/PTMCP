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

/** Cross-store password change: immutable pending operation, host CAS, metadata completion. */
public final class IdentityPasswordChanges {
    private static final String SELECT_OPERATION =
            "SELECT c.operation_id,c.auth_version,c.profile_version,c.expected_hash,c.target_hash,c.status,"
                    + "s.auth_version,s.identity_state,s.identity_change_id FROM identity_password_change c"
                    + " JOIN sys_user_security s ON s.user_id=c.user_id";
    private final DataSource primary;
    private final HostIdentityStore host;

    public IdentityPasswordChanges(DataSource primary, HostIdentityStore host) {
        this.primary = Objects.requireNonNull(primary, "primary");
        this.host = Objects.requireNonNull(host, "host");
    }

    public record Pending(
            UUID id,
            long userId,
            long authVersion,
            long profileVersion,
            byte[] expected,
            String target,
            boolean completed) {
        public Pending {
            expected = expected.clone();
        }

        @Override
        public byte[] expected() {
            return expected.clone();
        }

        @Override
        public String toString() {
            return "IdentityChange[redacted]";
        }
    }

    public long begin(
            long userId,
            long expectedProfileVersion,
            long capturedVersion,
            byte[] expectedHash,
            String target,
            long actorId) {
        if (userId <= 0
                || actorId <= 0
                || expectedProfileVersion <= 0
                || expectedProfileVersion == Long.MAX_VALUE
                || expectedHash == null
                || expectedHash.length != 32
                || target == null
                || target.isBlank()
                || target.length() > 100) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (capturedVersion <= 0 || capturedVersion == Long.MAX_VALUE)
            throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
        UUID operation = UUID.randomUUID();
        long next = capturedVersion + 1;
        new SecurityChangeTransaction(primary)
                .execute(
                        new SecurityEvent(
                                operation,
                                SecurityEvent.Kind.USER_SECURITY_CHANGED,
                                new SecurityEvent.NumericId(userId),
                                next),
                        connection -> {
                            UserSecurityStore.State state =
                                    UserSecurityStore.read(connection, userId, true);
                            UserSecurityStore.requireReady(state, capturedVersion);
                            UserSecurityStore.requireActiveProfile(connection, userId);
                            if (state.identityVersion() == Long.MAX_VALUE)
                                throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
                            try (PreparedStatement update =
                                    statement(
                                            connection,
                                            "UPDATE sys_user_profile SET password_hash=?,version=version+1,update_time=UTC_TIMESTAMP(6) WHERE user_id=? AND version=?")) {
                                update.setString(1, target);
                                update.setLong(2, userId);
                                update.setLong(3, expectedProfileVersion);
                                if (update.executeUpdate() != 1)
                                    throw new DomainException(ErrorCode.VERSION_CONFLICT);
                            }
                            try (PreparedStatement update =
                                    statement(
                                            connection,
                                            "UPDATE sys_user_security SET auth_version=?,identity_version=identity_version+1,identity_state='CHANGE_PENDING',"
                                                    + "identity_change_id=?,version=version+1,update_time=UTC_TIMESTAMP(6) WHERE user_id=?")) {
                                update.setLong(1, next);
                                update.setString(2, operation.toString());
                                update.setLong(3, userId);
                                if (update.executeUpdate() != 1)
                                    throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
                            }
                            try (PreparedStatement insert =
                                    statement(
                                            connection,
                                            "INSERT INTO identity_password_change (operation_id,user_id,auth_version,profile_version,expected_hash,target_hash,status,requested_by,create_time,update_time)"
                                                    + " VALUES (?,?,?,?,?,?,'PENDING',?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))")) {
                                insert.setString(1, operation.toString());
                                insert.setLong(2, userId);
                                insert.setLong(3, next);
                                insert.setLong(4, expectedProfileVersion + 1);
                                insert.setBytes(5, expectedHash);
                                insert.setString(6, target);
                                insert.setLong(7, actorId);
                                insert.executeUpdate();
                            }
                            UserSecurityStore.revokeCredentials(connection, userId);
                            try (PreparedStatement insert =
                                    statement(
                                            connection,
                                            "INSERT INTO admin_operation_log (actor_id,request_id,action,aggregate_type,aggregate_id,before_masked,after_masked,occurred_at,create_time)"
                                                    + " VALUES (?,?,'USER_PASSWORD_CHANGED','USER',?,'{}','{\"identityState\":\"CHANGE_PENDING\"}',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))")) {
                                insert.setLong(1, actorId);
                                insert.setString(2, operation.toString());
                                insert.setLong(3, userId);
                                insert.executeUpdate();
                            }
                        });
        // Bind post-commit work to this exact operation, never another operation found by user ID.
        return finish(
                new Pending(
                        operation,
                        userId,
                        next,
                        expectedProfileVersion + 1,
                        expectedHash,
                        target,
                        false));
    }

    public Pending pending(long userId) {
        return load(userId, null);
    }

    /** Also locates a completed operation so a lost completion response is safely recoverable. */
    public Pending operation(long userId, long profileVersion) {
        if (profileVersion <= 0) throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        return load(userId, profileVersion);
    }

    private Pending load(long userId, Long profileVersion) {
        String predicate =
                profileVersion == null
                        ? " WHERE c.user_id=? AND c.operation_id=s.identity_change_id AND c.status='PENDING'"
                        : " WHERE c.user_id=? AND c.profile_version=?";
        try (Connection connection = primary.getConnection();
                PreparedStatement query = statement(connection, SELECT_OPERATION + predicate)) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            query.setLong(1, userId);
            if (profileVersion != null) query.setLong(2, profileVersion);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) throw new DomainException(ErrorCode.VERSION_CONFLICT);
                Pending operation =
                        new Pending(
                                UUID.fromString(result.getString(1)),
                                userId,
                                result.getLong(2),
                                result.getLong(3),
                                result.getBytes(4),
                                result.getString(5),
                                "COMPLETED".equals(result.getString(6)));
                boolean bound =
                        operation.authVersion() == result.getLong(7)
                                && (operation.completed()
                                        ? "READY".equals(result.getString(8))
                                                && result.getString(9) == null
                                        : "CHANGE_PENDING".equals(result.getString(8))
                                                && operation
                                                        .id()
                                                        .toString()
                                                        .equals(result.getString(9)));
                if (!bound || result.next()) throw new DomainException(ErrorCode.VERSION_CONFLICT);
                return operation;
            }
        } catch (SQLException failure) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    public long finish(Pending operation) {
        // Pending is never replaced, cancelled or released on a timer. Ignore caller-carried
        // hashes.
        Pending current = operation(operation.userId(), operation.profileVersion());
        if (!current.id().equals(operation.id())
                || current.authVersion() != operation.authVersion())
            throw new DomainException(ErrorCode.VERSION_CONFLICT);
        if (current.completed()) return current.profileVersion();
        host.compareAndSetHash(current.userId(), current.expected(), current.target());
        new SecurityChangeTransaction(primary)
                .execute(
                        new SecurityEvent(
                                UUID.randomUUID(),
                                SecurityEvent.Kind.USER_SECURITY_CHANGED,
                                new SecurityEvent.NumericId(current.userId()),
                                current.authVersion()),
                        connection -> {
                            UserSecurityStore.State state =
                                    UserSecurityStore.read(connection, current.userId(), true);
                            String persistedStatus;
                            try (PreparedStatement query =
                                    statement(
                                            connection,
                                            "SELECT status FROM identity_password_change WHERE operation_id=? AND user_id=? AND auth_version=? AND profile_version=? FOR UPDATE")) {
                                query.setString(1, current.id().toString());
                                query.setLong(2, current.userId());
                                query.setLong(3, current.authVersion());
                                query.setLong(4, current.profileVersion());
                                try (ResultSet result = query.executeQuery()) {
                                    if (!result.next())
                                        throw new DomainException(ErrorCode.VERSION_CONFLICT);
                                    persistedStatus = result.getString(1);
                                }
                            }
                            if (state.ready()
                                    && state.authVersion() == current.authVersion()
                                    && "COMPLETED".equals(persistedStatus)) return;
                            if (!"PENDING".equals(persistedStatus))
                                throw new DomainException(ErrorCode.VERSION_CONFLICT);
                            UserSecurityStore.requireActiveProfile(connection, current.userId());
                            try (PreparedStatement complete =
                                    statement(
                                            connection,
                                            "UPDATE sys_user_security SET identity_state='READY',identity_change_id=NULL,password_changed_at=UTC_TIMESTAMP(6),"
                                                    + "version=version+1,update_time=UTC_TIMESTAMP(6) WHERE user_id=? AND auth_version=? AND identity_state='CHANGE_PENDING' AND identity_change_id=?")) {
                                complete.setLong(1, current.userId());
                                complete.setLong(2, current.authVersion());
                                complete.setString(3, current.id().toString());
                                if (complete.executeUpdate() != 1)
                                    throw new DomainException(ErrorCode.VERSION_CONFLICT);
                            }
                            try (PreparedStatement complete =
                                    statement(
                                            connection,
                                            "UPDATE identity_password_change SET status='COMPLETED',update_time=UTC_TIMESTAMP(6) WHERE operation_id=? AND status='PENDING'")) {
                                complete.setString(1, current.id().toString());
                                if (complete.executeUpdate() != 1)
                                    throw new DomainException(ErrorCode.VERSION_CONFLICT);
                            }
                        });
        return current.profileVersion();
    }

    private static PreparedStatement statement(Connection connection, String sql)
            throws SQLException {
        PreparedStatement result = connection.prepareStatement(sql);
        try {
            result.setQueryTimeout(5);
            return result;
        } catch (SQLException | RuntimeException failure) {
            try {
                result.close();
            } catch (SQLException | RuntimeException ignored) {
                /* Preserve the original failure. */
            }
            throw failure;
        }
    }
}
