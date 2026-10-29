package local.ptmcp.persistence.security;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.SecurityChangeTransaction;
import local.ptmcp.persistence.SecurityEvent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;

/** Cross-store password change: immutable pending operation, host CAS, metadata completion. */
public final class IdentityPasswordChanges {
    private final DataSource primary;
    private final HostIdentityStore host;
    public IdentityPasswordChanges(DataSource primary, HostIdentityStore host) { this.primary=primary; this.host=host; }

    public record Pending(UUID id,long userId,long authVersion,long profileVersion,byte[] expected,String target) {
        @Override public String toString() { return "IdentityChange[redacted]"; }
    }

    public long begin(long userId,long expectedProfileVersion,long capturedVersion,byte[] expectedHash,
            String target,long actorId) {
        if (capturedVersion<=0 || capturedVersion==Long.MAX_VALUE) throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
        UUID operation=UUID.randomUUID();
        long next=capturedVersion+1;
        new SecurityChangeTransaction(primary).execute(new SecurityEvent(operation,
                SecurityEvent.Kind.USER_SECURITY_CHANGED,new SecurityEvent.NumericId(userId),next), connection -> {
            UserSecurityStore.requireReady(UserSecurityStore.read(connection,userId,true),capturedVersion);
            try (PreparedStatement update=statement(connection,
                    "UPDATE sys_user_profile SET password_hash=?,version=version+1,update_time=UTC_TIMESTAMP(6)"
                    + " WHERE user_id=? AND version=?")) {
                update.setString(1,target);update.setLong(2,userId);update.setLong(3,expectedProfileVersion);
                if(update.executeUpdate()!=1) throw new DomainException(ErrorCode.VERSION_CONFLICT);
            }
            try (PreparedStatement update=statement(connection,
                    "UPDATE sys_user_security SET auth_version=?,identity_version=identity_version+1,"
                    + "identity_state='CHANGE_PENDING',identity_change_id=?,version=version+1,update_time=UTC_TIMESTAMP(6) WHERE user_id=?")) {
                update.setLong(1,next);update.setString(2,operation.toString());update.setLong(3,userId);update.executeUpdate();
            }
            try(PreparedStatement insert=statement(connection,
                    "INSERT INTO identity_password_change (operation_id,user_id,auth_version,profile_version,expected_hash,target_hash,status,requested_by,create_time,update_time)"
                    + " VALUES (?,?,?,?,?,?,'PENDING',?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))")) {
                insert.setString(1,operation.toString());insert.setLong(2,userId);insert.setLong(3,next);
                insert.setLong(4,expectedProfileVersion+1);insert.setBytes(5,expectedHash);insert.setString(6,target);insert.setLong(7,actorId);insert.executeUpdate();
            }
            UserSecurityStore.revokeCredentials(connection,userId);
            try(PreparedStatement insert=statement(connection,
                    "INSERT INTO admin_operation_log (actor_id,request_id,action,aggregate_type,aggregate_id,before_masked,after_masked,occurred_at,create_time)"
                    + " VALUES (?,?,'USER_PASSWORD_CHANGED','USER',?,'{}','{\"identityState\":\"CHANGE_PENDING\"}',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))")) {
                insert.setLong(1,actorId);insert.setString(2,operation.toString());insert.setLong(3,userId);insert.executeUpdate();
            }
        });
        return finish(pending(userId));
    }

    public Pending pending(long userId) {
        try(Connection connection=primary.getConnection();PreparedStatement query=statement(connection,
                "SELECT c.operation_id,c.auth_version,c.profile_version,c.expected_hash,c.target_hash FROM identity_password_change c"
                + " JOIN sys_user_security s ON s.user_id=c.user_id AND s.identity_change_id=c.operation_id"
                + " WHERE c.user_id=? AND c.status='PENDING' AND s.identity_state='CHANGE_PENDING' AND s.auth_version=c.auth_version")) {
            query.setLong(1,userId);
            try(ResultSet result=query.executeQuery()) {
                if(!result.next()) throw new DomainException(ErrorCode.VERSION_CONFLICT);
                return new Pending(UUID.fromString(result.getString(1)),userId,result.getLong(2),result.getLong(3),result.getBytes(4),result.getString(5));
            }
        }catch(SQLException failure){throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);}
    }

    public long finish(Pending operation) {
        // Re-read on every attempt. Pending is never replaced, cancelled or released on a timer.
        Pending current=pending(operation.userId());
        if(!current.id().equals(operation.id())) throw new DomainException(ErrorCode.VERSION_CONFLICT);
        host.compareAndSetHash(current.userId(),current.expected(),current.target());
        new SecurityChangeTransaction(primary).execute(new SecurityEvent(UUID.randomUUID(),
                SecurityEvent.Kind.USER_SECURITY_CHANGED,new SecurityEvent.NumericId(current.userId()),current.authVersion()), connection -> {
            UserSecurityStore.State state=UserSecurityStore.read(connection,current.userId(),true);
            try(PreparedStatement complete=statement(connection,
                    "UPDATE sys_user_security SET identity_state='READY',identity_change_id=NULL,password_changed_at=UTC_TIMESTAMP(6),"
                    + "version=version+1,update_time=UTC_TIMESTAMP(6) WHERE user_id=? AND auth_version=? AND identity_state='CHANGE_PENDING' AND identity_change_id=?")) {
                complete.setLong(1,current.userId());complete.setLong(2,current.authVersion());complete.setString(3,current.id().toString());
                if(complete.executeUpdate()!=1) {
                    if(state.ready() && state.authVersion()==current.authVersion()) return;
                    throw new DomainException(ErrorCode.VERSION_CONFLICT);
                }
            }
            try(PreparedStatement complete=statement(connection,
                    "UPDATE identity_password_change SET status='COMPLETED',update_time=UTC_TIMESTAMP(6) WHERE operation_id=? AND status='PENDING'")) {
                complete.setString(1,current.id().toString());
                if(complete.executeUpdate()!=1) throw new DomainException(ErrorCode.VERSION_CONFLICT);
            }
        });
        return current.profileVersion();
    }
    private static PreparedStatement statement(Connection connection,String sql) throws SQLException {
        PreparedStatement result=connection.prepareStatement(sql);result.setQueryTimeout(5);return result;
    }
}
