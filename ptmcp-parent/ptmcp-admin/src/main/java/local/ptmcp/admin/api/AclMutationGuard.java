package local.ptmcp.admin.api;

import local.ptmcp.common.authz.GrantRow;
import local.ptmcp.common.authz.PermissionGrant;
import local.ptmcp.common.authz.SubjectType;
import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.org.AclDirectory;
import local.ptmcp.persistence.org.AclGrantStore;
import local.ptmcp.persistence.org.ResourceIds;
import local.ptmcp.persistence.org.SelfEscalationGuard;
import local.ptmcp.persistence.security.UserSecurityStore;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;

/**
 * Per-command policy evaluated on the metadata write connection, never from an old HTTP snapshot.
 */
final class AclMutationGuard {
    private final AclDirectory directory;

    AclMutationGuard(AclDirectory directory) {
        this.directory = directory;
    }

    AclGrantStore.MutationCheck policy(long actorId, Long expectedAuthVersion) {
        return new AclGrantStore.MutationCheck() {
            @Override
            public void authorize(Connection connection) throws SQLException {
                UserSecurityStore.State user = UserSecurityStore.read(connection, actorId, true);
                UserSecurityStore.requireReady(
                        user,
                        expectedAuthVersion == null ? user.authVersion() : expectedAuthVersion);
                UserSecurityStore.requireActiveProfile(connection, actorId);
                if (!UserSecurityStore.isPlatformAdmin(connection, actorId)) {
                    throw new DomainException(ErrorCode.MANAGEMENT_SCOPE_DENIED);
                }
            }

            @Override
            public void validate(
                    Connection connection,
                    AclGrantStore.AclGrantRecord before,
                    AclGrantStore.AclGrantRecord after)
                    throws SQLException {
                var subjects = directory.subjectScope(connection, actorId);
                var original = directory.activeGrants(connection, actorId);
                var replacement = new ArrayList<>(original);
                if (before != null) replacement.removeIf(grant -> grant.grantId() == before.id());
                if (after != null)
                    replacement.add(toGrant(after, before == null ? Long.MAX_VALUE : before.id()));
                SelfEscalationGuard.assertNoEscalation(
                        subjects,
                        original,
                        replacement,
                        directory.resourceTree(connection),
                        true,
                        true,
                        Instant.now());
            }
        };
    }

    private static GrantRow toGrant(AclGrantStore.AclGrantRecord row, long id) {
        return new GrantRow(
                SubjectType.valueOf(row.subjectType()),
                row.subjectId(),
                ResourceIds.encode(ResourceIds.Type.valueOf(row.resourceType()), row.resourceId()),
                PermissionGrant.Action.valueOf(row.action()),
                PermissionGrant.Effect.valueOf(row.effect()),
                row.includeDescendants(),
                row.validFrom(),
                row.validTo(),
                id);
    }
}
