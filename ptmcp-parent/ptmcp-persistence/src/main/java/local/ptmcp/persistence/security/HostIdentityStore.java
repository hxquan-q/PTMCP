package local.ptmcp.persistence.security;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Locale;

import javax.sql.DataSource;

/** The host owns sys_user; this adapter never creates or migrates its schema. */
public final class HostIdentityStore {
    private final DataSource identity;

    public HostIdentityStore(DataSource identity) {
        this.identity = Objects.requireNonNull(identity, "identity");
    }

    public record Account(
            long userId, String loginName, String displayName, String hash, boolean active) {
        public boolean boundTo(String normalizedName) {
            return normalizedName != null && normalizedName.equals(normalizeLoginName(loginName));
        }

        @Override
        public String toString() {
            return "HostAccount[redacted]";
        }
    }

    /** Shared host-compatible canonical login name; metadata remains the binding authority. */
    public static String normalizeLoginName(String value) {
        if (value == null) return null;
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) > 0x7f) return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.matches("[a-z0-9_.-]{1,30}") ? normalized : null;
    }

    public Account read(long userId) {
        try (Connection connection = identity.getConnection()) {
            return read(connection, userId, false);
        } catch (SQLException | RuntimeException failure) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private static Account read(Connection connection, long userId, boolean lock)
            throws SQLException {
        try (PreparedStatement statement =
                statement(
                        connection,
                        "SELECT user_name,nick_name,password,status,del_flag FROM sys_user WHERE user_id=?"
                                + (lock ? " FOR UPDATE" : ""))) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                return new Account(
                        userId,
                        result.getString(1),
                        result.getString(2),
                        result.getString(3),
                        "0".equals(result.getString(4)) && "0".equals(result.getString(5)));
            }
        }
    }

    /** A late operation can only replace its own captured predecessor, never a newer hash. */
    public void compareAndSetHash(long userId, String normalizedName, byte[] expected, String target) {
        if (userId <= 0
                || normalizedName == null
                || !normalizedName.equals(normalizeLoginName(normalizedName))
                || expected == null
                || expected.length != 32
                || target == null
                || target.isBlank()
                || target.length() > 100) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        Connection connection = null;
        boolean started = false;
        boolean commitAttempted = false;
        boolean committed = false;
        DomainException failure = null;
        try {
            connection = identity.getConnection();
            if (!connection.getAutoCommit())
                throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
            connection.setReadOnly(false);
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try (PreparedStatement query =
                            statement(
                                    connection,
                                    "SELECT @@global.read_only,@@global.super_read_only");
                    ResultSet result = query.executeQuery()) {
                if (!result.next() || result.getBoolean(1) || result.getBoolean(2))
                    throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
            }
            try (PreparedStatement setting =
                    statement(connection, "SET SESSION innodb_lock_wait_timeout=5")) {
                setting.executeUpdate();
            }
            connection.setAutoCommit(false);
            started = true;
            Account bound = read(connection, userId, true);
            if (bound == null || !bound.active() || !bound.boundTo(normalizedName))
                throw new DomainException(ErrorCode.VERSION_CONFLICT);
            int updated;
            try (PreparedStatement update =
                    statement(
                            connection,
                            "UPDATE sys_user SET password=? WHERE user_id=? AND status='0' AND del_flag='0' AND UNHEX(SHA2(password,256))=?")) {
                update.setString(1, target);
                update.setLong(2, userId);
                update.setBytes(3, expected);
                updated = update.executeUpdate();
            }
            if (updated != 1) {
                // A current read under the same transaction also serializes concurrent finishers.
                Account current = read(connection, userId, true);
                if (updated != 0
                        || current == null
                        || !current.active()
                        || !Objects.equals(current.hash(), target)) {
                    throw new DomainException(ErrorCode.VERSION_CONFLICT);
                }
            }
            commitAttempted = true;
            connection.commit();
            committed = true;
        } catch (SQLException error) {
            failure =
                    new DomainException(
                            commitAttempted
                                    ? ErrorCode.OPERATION_UNKNOWN
                                    : ErrorCode.DEPENDENCY_UNAVAILABLE);
        } catch (RuntimeException error) {
            failure =
                    new DomainException(
                            commitAttempted
                                    ? ErrorCode.OPERATION_UNKNOWN
                                    : error instanceof DomainException domain
                                            ? domain.errorCode()
                                            : ErrorCode.DEPENDENCY_UNAVAILABLE);
        } finally {
            if (connection != null) {
                if (started && !committed) {
                    try {
                        connection.rollback();
                    } catch (SQLException | RuntimeException error) {
                        failure = new DomainException(ErrorCode.OPERATION_UNKNOWN);
                    }
                }
                try {
                    connection.close();
                } catch (SQLException | RuntimeException error) {
                    // A confirmed commit remains confirmed. Discard without replaying the write.
                    try {
                        connection.abort(Runnable::run);
                    } catch (SQLException | RuntimeException ignored) {
                        /* No infrastructure details cross this seam. */
                    }
                }
            }
        }
        if (failure != null) throw failure;
    }

    public static byte[] fingerprint(String hash) {
        if (hash == null || hash.isBlank())
            throw new DomainException(ErrorCode.AUTHENTICATION_FAILED);
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(hash.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
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
