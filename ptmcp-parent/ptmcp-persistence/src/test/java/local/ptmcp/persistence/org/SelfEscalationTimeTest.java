package local.ptmcp.persistence.org;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import local.ptmcp.common.authz.GrantRow;
import local.ptmcp.common.authz.PermissionGrant;
import local.ptmcp.common.authz.ResourceNode;
import local.ptmcp.common.authz.SubjectScope;
import local.ptmcp.common.authz.SubjectType;
import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

class SelfEscalationTimeTest {
    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");
    private static final SubjectScope CALLER = new SubjectScope(7, Set.of(2L), null, Set.of());
    private static final List<ResourceNode> TREE = List.of(new ResourceNode(10, null));

    @Test
    void futureUserAllowIsNotNeutralAtAdmission() {
        GrantRow future =
                grant(
                        1,
                        SubjectType.USER,
                        7,
                        PermissionGrant.Effect.ALLOW,
                        NOW.plusSeconds(60),
                        null);
        assertEscalation(List.of(), List.of(future));
    }

    @Test
    void anExistingMetaDenyExpiryCannotHideNewAllow() {
        GrantRow deny =
                new GrantRow(
                        SubjectType.USER,
                        7,
                        10,
                        PermissionGrant.Action.META,
                        PermissionGrant.Effect.DENY,
                        false,
                        NOW.minusSeconds(60),
                        NOW.plusSeconds(120),
                        1);
        GrantRow allow =
                grant(
                        2,
                        SubjectType.USER,
                        7,
                        PermissionGrant.Effect.ALLOW,
                        NOW.minusSeconds(60),
                        null);
        assertEscalation(List.of(deny), List.of(deny, allow));
    }

    @Test
    void futureRoleAllowIsAlsoSelfEscalation() {
        GrantRow future =
                grant(
                        1,
                        SubjectType.ROLE,
                        2,
                        PermissionGrant.Effect.ALLOW,
                        NOW.plusSeconds(60),
                        NOW.plusSeconds(120));
        assertEscalation(List.of(), List.of(future));
    }

    @Test
    void futurePermissionsAlreadyHeldRemainNeutral() {
        GrantRow future =
                grant(
                        1,
                        SubjectType.USER,
                        7,
                        PermissionGrant.Effect.ALLOW,
                        NOW.plusSeconds(60),
                        null);
        assertDoesNotThrow(() -> check(List.of(future), List.of(future)));
    }

    @Test
    void removingAlreadyExpiredDenyDoesNotCreateNewRights() {
        GrantRow deny =
                grant(
                        1,
                        SubjectType.USER,
                        7,
                        PermissionGrant.Effect.DENY,
                        NOW.minusSeconds(120),
                        NOW.minusSeconds(60));
        GrantRow allow =
                grant(
                        2,
                        SubjectType.USER,
                        7,
                        PermissionGrant.Effect.ALLOW,
                        NOW.minusSeconds(120),
                        null);
        assertDoesNotThrow(() -> check(List.of(deny, allow), List.of(allow)));
    }

    private static GrantRow grant(
            long id,
            SubjectType type,
            long subject,
            PermissionGrant.Effect effect,
            Instant from,
            Instant to) {
        return new GrantRow(
                type, subject, 10, PermissionGrant.Action.READ, effect, false, from, to, id);
    }

    private static void assertEscalation(List<GrantRow> before, List<GrantRow> after) {
        DomainException failure = assertThrows(DomainException.class, () -> check(before, after));
        assertEquals(ErrorCode.SELF_PRIVILEGE_ESCALATION, failure.errorCode());
    }

    private static void check(List<GrantRow> before, List<GrantRow> after) {
        SelfEscalationGuard.assertNoEscalation(CALLER, before, after, TREE, true, true, NOW);
    }
}
