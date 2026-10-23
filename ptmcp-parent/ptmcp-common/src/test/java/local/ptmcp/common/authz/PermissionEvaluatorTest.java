package local.ptmcp.common.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import local.ptmcp.common.authz.PermissionGrant.Action;
import local.ptmcp.common.authz.PermissionGrant.Effect;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

class PermissionEvaluatorTest {
    private static final Instant T0 = Instant.parse("2026-10-24T00:00:00Z");
    private static final Instant T1 = T0.plusSeconds(3600);
    private static final Instant T2 = T0.plusSeconds(7200);

    private static PermissionGrant grant(
            long id, Effect effect, Action action, Instant from, Instant to) {
        return new PermissionGrant(id, effect, action, from, to);
    }

    private static PermissionEvaluator.Decision evaluate(
            List<PermissionGrant> grants, Instant now) {
        return PermissionEvaluator.evaluate(grants, true, true, now);
    }

    @Test
    void noGrantsMeansNoAccess() {
        PermissionEvaluator.Decision decision = evaluate(List.of(), T0);

        assertFalse(decision.meta());
        assertFalse(decision.read());
        assertTrue(decision.nextBoundaryAt().isEmpty());
    }

    @Test
    void readAllowAlsoImpliesMetaWithinItsWindow() {
        PermissionGrant read = grant(1, Effect.ALLOW, Action.READ, T0, T1);

        PermissionEvaluator.Decision before = evaluate(List.of(read), T0.minusSeconds(1));
        PermissionEvaluator.Decision inside = evaluate(List.of(read), T0.plusSeconds(1));

        assertFalse(before.read());
        assertFalse(before.meta());
        assertEquals(T0, before.nextBoundaryAt().orElseThrow());
        assertTrue(inside.read());
        assertTrue(inside.meta());
        assertEquals(List.of(1L), inside.allowGrantIds());
    }

    @Test
    void endOfWindowIsExclusiveAndReportedAsBoundary() {
        PermissionGrant read = grant(1, Effect.ALLOW, Action.READ, T0, T1);

        PermissionEvaluator.Decision atEnd = evaluate(List.of(read), T1);

        assertFalse(atEnd.read());
        assertFalse(atEnd.meta());
        assertTrue(atEnd.nextBoundaryAt().isEmpty());
    }

    @Test
    void metaDenyBlocksReadWhileReadDenyKeepsMeta() {
        PermissionGrant readAllow = grant(1, Effect.ALLOW, Action.READ, T0, T1);
        PermissionGrant readDeny = grant(2, Effect.DENY, Action.READ, T0, T1);

        PermissionEvaluator.Decision readDenied = evaluate(List.of(readAllow, readDeny), T0);
        assertTrue(readDenied.meta());
        assertFalse(readDenied.read());
        assertEquals(List.of(2L), readDenied.denyGrantIds());

        PermissionEvaluator.Decision metaDenied =
                evaluate(List.of(readAllow, grant(3, Effect.DENY, Action.META, T0, T1)), T0);
        assertFalse(metaDenied.meta());
        assertFalse(metaDenied.read());
        assertTrue(metaDenied.denyGrantIds().contains(3L));
    }

    @Test
    void futureDenyOnlyAppliesFromItsStartAndIsTheNextBoundary() {
        PermissionGrant allow = grant(1, Effect.ALLOW, Action.READ, T0, null);
        PermissionGrant futureDeny = grant(2, Effect.DENY, Action.READ, T1, null);

        PermissionEvaluator.Decision now = evaluate(List.of(allow, futureDeny), T0);
        PermissionEvaluator.Decision later = evaluate(List.of(allow, futureDeny), T1);

        assertTrue(now.read());
        assertEquals(T1, now.nextBoundaryAt().orElseThrow());
        assertFalse(later.read());
    }

    @Test
    void earliestUpcomingBoundaryWinsAcrossGrants() {
        PermissionGrant later = grant(1, Effect.ALLOW, Action.READ, T2, null);
        PermissionGrant sooner = grant(2, Effect.ALLOW, Action.READ, T0, T1);
        PermissionGrant expired = grant(3, Effect.ALLOW, Action.READ, T0.minusSeconds(60), T0);

        PermissionEvaluator.Decision decision = evaluate(List.of(later, sooner, expired), T0);

        assertEquals(T1, decision.nextBoundaryAt().orElseThrow());
        assertEquals(List.of(2L), decision.allowGrantIds());
    }

    @Test
    void platformCeilingCannotBeExceeded() {
        PermissionGrant allow = grant(1, Effect.ALLOW, Action.READ, T0, null);

        PermissionEvaluator.Decision readCapped =
                PermissionEvaluator.evaluate(List.of(allow), true, false, T0);
        PermissionEvaluator.Decision metaCapped =
                PermissionEvaluator.evaluate(List.of(allow), false, true, T0);

        // ResourceRead = UserRead ∩ PlatformRead: platform READ alone still bounds the row access,
        // while a platform without META cannot disclose structure even when the user may read rows.
        assertFalse(readCapped.read());
        assertTrue(metaCapped.read());
        assertFalse(metaCapped.meta());
    }

    @Test
    void denyBeatsAllowForTheSameActionRegardlessOfOrder() {
        PermissionGrant allow = grant(1, Effect.ALLOW, Action.READ, T0, null);
        PermissionGrant deny = grant(2, Effect.DENY, Action.READ, T0, null);

        assertFalse(evaluate(List.of(allow, deny), T0).read());
        assertFalse(evaluate(List.of(deny, allow), T0).read());
    }

    @Test
    void invalidWindowsAndIdentifiersAreRejected() {
        assertThrows(
                IllegalArgumentException.class, () -> grant(1, Effect.ALLOW, Action.READ, T1, T0));
        assertThrows(
                IllegalArgumentException.class, () -> grant(1, Effect.ALLOW, Action.READ, T0, T0));
        assertThrows(
                IllegalArgumentException.class,
                () -> grant(0, Effect.ALLOW, Action.READ, T0, null));
        assertThrows(
                NullPointerException.class,
                () -> new PermissionGrant(1, null, Action.READ, T0, null));
    }
}
