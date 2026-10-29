package local.ptmcp.admin.identity;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.security.HostIdentityStore;
import local.ptmcp.persistence.security.IdentityPasswordChanges;
import local.ptmcp.persistence.security.UserSecurityStore;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import javax.sql.DataSource;

/** A failed host write leaves an explicit, recoverable login gate; never a working old password. */
public final class IdentityPasswordService {
    private final DataSource primary;
    private final HostIdentityStore host;
    private final IdentityPasswordChanges changes;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public IdentityPasswordService(DataSource primary, IdentityPasswordWriter writer) {
        this.primary = primary;
        this.host = writer.host();
        this.changes = new IdentityPasswordChanges(primary, host);
    }

    public long reset(
            long userId, long expectedVersion, String newPassword, String reason, long operatorId) {
        requirePassword(newPassword);
        if (userId <= 0
                || expectedVersion <= 0
                || operatorId <= 0
                || reason == null
                || reason.trim().length() < 10
                || reason.length() > 500) throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        UserSecurityStore.State captured = new UserSecurityStore(primary).read(userId);
        UserSecurityStore.requireReady(captured, captured.authVersion());
        HostIdentityStore.Account account = host.read(userId);
        String loginName = new UserSecurityStore(primary).loginName(userId);
        if (account == null || !account.active() || !account.boundTo(loginName))
            throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
        return changes.begin(
                userId,
                expectedVersion,
                captured.authVersion(),
                HostIdentityStore.fingerprint(account.hash()),
                encoder.encode(newPassword),
                operatorId);
    }

    /**
     * Retries the persisted target; no new password, operation, version bump or credential revival.
     */
    public long retry(long userId, long expectedVersion) {
        if (expectedVersion <= 0) throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        if (status(userId).version() != expectedVersion)
            throw new DomainException(ErrorCode.VERSION_CONFLICT);
        IdentityPasswordChanges.Pending operation = changes.operation(userId, expectedVersion);
        if (operation.profileVersion() != expectedVersion)
            throw new DomainException(ErrorCode.VERSION_CONFLICT);
        return changes.finish(operation);
    }

    public record Status(
            long userId, long version, String identityState, boolean recoveryRequired) {}

    /** One primary statement returns only public recovery state, never operation material. */
    public Status status(long userId) {
        if (userId <= 0) throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        try (Connection connection = primary.getConnection();
                PreparedStatement query =
                        connection.prepareStatement(
                                "SELECT p.version,s.identity_state FROM sys_user_profile p"
                                        + " LEFT JOIN sys_user_security s ON s.user_id=p.user_id WHERE p.user_id=?")) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            query.setQueryTimeout(5);
            query.setLong(1, userId);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
                long version = result.getLong(1);
                String state = result.getString(2);
                if (version <= 0
                        || !("READY".equals(state)
                                || "CHANGE_PENDING".equals(state)
                                || "UNBOUND".equals(state)))
                    throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
                return new Status(userId, version, state, "CHANGE_PENDING".equals(state));
            }
        } catch (SQLException failure) {
            throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
        }
    }

    public static void requirePassword(String value) {
        if (value == null
                || value.length() < 12
                || value.length() > 64
                || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
    }
}
