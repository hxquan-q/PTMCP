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
import javax.sql.DataSource;

/** The host owns sys_user; this adapter never creates or migrates its schema. */
public final class HostIdentityStore {
    private final DataSource identity;
    public HostIdentityStore(DataSource identity) { this.identity = Objects.requireNonNull(identity); }

    public record Account(long userId, String loginName, String displayName, String hash, boolean active) {
        @Override public String toString() { return "HostAccount[redacted]"; }
    }

    public Account read(long userId) {
        try (Connection connection = identity.getConnection()) {
            return read(connection, userId, false);
        } catch (SQLException failure) { throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE); }
    }

    private static Account read(Connection connection, long userId, boolean lock) throws SQLException {
        try (PreparedStatement statement = statement(connection,
                "SELECT user_name,nick_name,password,status,del_flag FROM sys_user WHERE user_id=?" + (lock ? " FOR UPDATE" : ""))) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return null;
                return new Account(userId,result.getString(1),result.getString(2),result.getString(3),
                        "0".equals(result.getString(4)) && "0".equals(result.getString(5)));
            }
        }
    }

    /** A late operation can only replace its own captured predecessor, never a newer hash. */
    public void compareAndSetHash(long userId, byte[] expected, String target) {
        boolean commitAttempted = false;
        try (Connection connection = identity.getConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            try {
                int updated;
                try (PreparedStatement statement = statement(connection,
                        "UPDATE sys_user SET password=? WHERE user_id=? AND status='0' AND del_flag='0'"
                        + " AND UNHEX(SHA2(password,256))=?")) {
                    statement.setString(1,target); statement.setLong(2,userId); statement.setBytes(3,expected);
                    updated=statement.executeUpdate();
                }
                if (updated != 1) {
                    Account current=read(connection,userId,true);
                    if (current == null || !current.active() || !Objects.equals(current.hash(),target)) {
                        throw new DomainException(ErrorCode.VERSION_CONFLICT);
                    }
                }
                commitAttempted = true;
                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); } catch (SQLException ignored) { /* Preserve the outcome. */ }
                throw failure;
            }
        } catch (SQLException failure) {
            throw new DomainException(commitAttempted ? ErrorCode.OPERATION_UNKNOWN : ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    public static byte[] fingerprint(String hash) {
        if (hash == null || hash.isBlank()) throw new DomainException(ErrorCode.AUTHENTICATION_FAILED);
        try { return MessageDigest.getInstance("SHA-256").digest(hash.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static PreparedStatement statement(Connection connection,String sql) throws SQLException {
        PreparedStatement result=connection.prepareStatement(sql); result.setQueryTimeout(5); return result;
    }
}
