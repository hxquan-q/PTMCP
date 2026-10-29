package local.ptmcp.admin.identity;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.security.HostIdentityStore;
import local.ptmcp.persistence.security.IdentityPasswordChanges;
import local.ptmcp.persistence.security.UserSecurityStore;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import javax.sql.DataSource;

/** A failed host write leaves an explicit, recoverable login gate; never a working old password. */
public final class IdentityPasswordService {
    private final DataSource primary;
    private final HostIdentityStore host;
    private final IdentityPasswordChanges changes;
    private final BCryptPasswordEncoder encoder=new BCryptPasswordEncoder();
    public IdentityPasswordService(DataSource primary,IdentityPasswordWriter writer) {
        this.primary=primary; this.host=writer.host(); this.changes=new IdentityPasswordChanges(primary,host);
    }
    public long reset(long userId,long expectedVersion,String newPassword,String reason,long operatorId) {
        requirePassword(newPassword);
        if(userId<=0 || expectedVersion<=0 || operatorId<=0 || reason==null || reason.trim().length()<10 || reason.length()>500)
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        UserSecurityStore.State captured=new UserSecurityStore(primary).read(userId);
        UserSecurityStore.requireReady(captured,captured.authVersion());
        HostIdentityStore.Account account=host.read(userId);
        if(account==null || !account.active()) throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
        return changes.begin(userId,expectedVersion,captured.authVersion(),HostIdentityStore.fingerprint(account.hash()),encoder.encode(newPassword),operatorId);
    }
    /** Retries the persisted target; no new password, operation, version bump or credential revival. */
    public long retry(long userId,long expectedVersion) {
        IdentityPasswordChanges.Pending operation=changes.pending(userId);
        if(operation.profileVersion()!=expectedVersion) throw new DomainException(ErrorCode.VERSION_CONFLICT);
        return changes.finish(operation);
    }
    public static void requirePassword(String value) {
        if(value==null || value.length()<12 || value.length()>64 || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>72)
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
    }
}
