package local.ptmcp.admin.identity;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.security.HostIdentityStore;
import local.ptmcp.persistence.security.IdentityPasswordChanges;
import local.ptmcp.persistence.security.IdentityProvisions;
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
    private final IdentityProvisions provisions;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public IdentityPasswordService(DataSource primary, IdentityPasswordWriter writer) {
        this.primary = primary;
        this.host = writer.host();
        this.changes = new IdentityPasswordChanges(primary, host);
        this.provisions = new IdentityProvisions(primary);
    }

    public long reset(
            long userId, long expectedVersion, String newPassword, String reason, long operatorId) {
        try {
            return resetChecked(userId, expectedVersion, newPassword, reason, operatorId);
        } catch (DomainException failure) {
            // TOKEN_REVOKED means the target is not usable here, never the caller's session; a 401
            // would make the admin client discard its own live session.
            throw targetStateConflict(failure);
        }
    }

    private long resetChecked(
            long userId, long expectedVersion, String newPassword, String reason, long operatorId) {
        requirePassword(newPassword);
        if (userId <= 0
                || expectedVersion <= 0
                || operatorId <= 0
                || reason == null
                || reason.trim().length() < 10
                || reason.length() > 500) throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        UserSecurityStore.State captured = new UserSecurityStore(primary).read(userId);
        if ("UNBOUND".equals(captured.identityState())) {
            // A persisted target already exists after a failed host write; only retry may use it.
            if (provisions.pending(userId))
                throw new DomainException(ErrorCode.OPERATION_IN_PROGRESS);
            String loginName = new UserSecurityStore(primary).loginName(userId);
            if (host.read(userId) != null || host.byLoginName(loginName) != null)
                throw new DomainException(ErrorCode.RESOURCE_AMBIGUOUS);
            IdentityProvisions.Pending operation =
                    provisions.begin(
                            userId, expectedVersion, operatorId, encoder.encode(newPassword));
            return finishProvision(operation);
        }
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
        try {
            return retryChecked(userId, expectedVersion);
        } catch (DomainException failure) {
            throw targetStateConflict(failure);
        }
    }

    private long retryChecked(long userId, long expectedVersion) {
        if (expectedVersion <= 0) throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        Status status = status(userId);
        if (status.version() != expectedVersion)
            throw new DomainException(ErrorCode.VERSION_CONFLICT);
        IdentityProvisions.Pending provision = provisions.findIfPresent(userId, expectedVersion);
        if (provision != null) {
            if (provision.completed()) return provision.profileVersion();
            return finishProvision(provision);
        }
        IdentityPasswordChanges.Pending operation = changes.operation(userId, expectedVersion);
        if (operation.profileVersion() != expectedVersion)
            throw new DomainException(ErrorCode.VERSION_CONFLICT);
        return changes.finish(operation);
    }

    private long finishProvision(IdentityProvisions.Pending operation) {
        UserSecurityStore security = new UserSecurityStore(primary);
        UserSecurityStore.State current = security.read(operation.userId());
        // Recheck before the host side effect: a stale intent must not create a usable host
        // credential for an identity that has moved on in the metadata store.
        if (!"UNBOUND".equals(current.identityState())
                || current.authVersion() != operation.authVersion())
            throw new DomainException(ErrorCode.VERSION_CONFLICT);
        security.requireActiveProfile(operation.userId());
        String loginName = security.loginName(operation.userId());
        host.provision(
                operation.userId(),
                loginName,
                security.displayName(operation.userId()),
                operation.target());
        return provisions.complete(operation);
    }

    private static DomainException targetStateConflict(DomainException failure) {
        return failure.errorCode() == ErrorCode.TOKEN_REVOKED
                ? new DomainException(ErrorCode.VERSION_CONFLICT)
                : failure;
    }

    public record Status(
            long userId, long version, String identityState, boolean recoveryRequired) {}

    /** One primary statement returns only public recovery state, never operation material. */
    public Status status(long userId) {
        if (userId <= 0) throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        try (Connection connection = primary.getConnection();
                PreparedStatement query =
                        connection.prepareStatement(
                                "SELECT p.version,s.identity_state,"
                                        + "EXISTS(SELECT 1 FROM identity_provision o"
                                        + " WHERE o.user_id=p.user_id AND o.status='PENDING')"
                                        + " FROM sys_user_profile p"
                                        + " LEFT JOIN sys_user_security s ON s.user_id=p.user_id"
                                        + " WHERE p.user_id=?")) {
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
                boolean recoveryRequired =
                        "CHANGE_PENDING".equals(state)
                                || ("UNBOUND".equals(state) && result.getBoolean(3));
                return new Status(userId, version, state, recoveryRequired);
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
