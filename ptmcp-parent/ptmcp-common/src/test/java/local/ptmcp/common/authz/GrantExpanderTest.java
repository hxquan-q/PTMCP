package local.ptmcp.common.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

class GrantExpanderTest {
    private static final Instant NOW = Instant.parse("2026-10-24T00:00:00Z");

    private static final SubjectScope SUBJECT =
            new SubjectScope(100, Set.of(200L, 201L), 300L, Set.of(400L));

    private static final List<ResourceNode> TREE =
            List.of(
                    new ResourceNode(1, null),
                    new ResourceNode(2, 1L),
                    new ResourceNode(3, 2L),
                    new ResourceNode(10, null));

    @Test
    void expandsRoleAllowAndPersonalDenyForTheSameTarget() {
        List<GrantRow> grants =
                List.of(
                        grant(
                                1,
                                SubjectType.ROLE,
                                200,
                                1,
                                PermissionGrant.Effect.ALLOW,
                                true,
                                null),
                        grant(
                                2,
                                SubjectType.USER,
                                100,
                                3,
                                PermissionGrant.Effect.DENY,
                                false,
                                null));

        List<PermissionGrant> expanded = GrantExpander.expand(grants, SUBJECT, TREE, 3);

        assertEquals(2, expanded.size());
        PermissionEvaluator.Decision decision =
                PermissionEvaluator.evaluate(expanded, true, true, NOW);
        assertFalse(decision.read(), "a personal DENY must beat the role ALLOW");
    }

    @Test
    void departmentAncestorGrantInheritsDownwardsButNotSideways() {
        List<GrantRow> grants =
                List.of(
                        grant(
                                3,
                                SubjectType.DEPT,
                                400,
                                2,
                                PermissionGrant.Effect.ALLOW,
                                true,
                                null));

        assertEquals(1, GrantExpander.expand(grants, SUBJECT, TREE, 3).size());
        assertTrue(GrantExpander.expand(grants, SUBJECT, TREE, 10).isEmpty());
    }

    @Test
    void includeDescendantsFalseDoesNotReachChildren() {
        GrantRow exactOnly =
                grant(4, SubjectType.USER, 100, 2, PermissionGrant.Effect.ALLOW, false, null);

        assertEquals(1, GrantExpander.expand(List.of(exactOnly), SUBJECT, TREE, 2).size());
        assertTrue(GrantExpander.expand(List.of(exactOnly), SUBJECT, TREE, 3).isEmpty());
    }

    @Test
    void grantOnDescendantNeverReachesAnAncestor() {
        GrantRow onChild =
                grant(5, SubjectType.USER, 100, 3, PermissionGrant.Effect.ALLOW, true, null);

        assertTrue(GrantExpander.expand(List.of(onChild), SUBJECT, TREE, 2).isEmpty());
    }

    @Test
    void disabledRolesAndUnrelatedSubjectsDoNotContribute() {
        List<GrantRow> grants =
                List.of(
                        grant(
                                6,
                                SubjectType.ROLE,
                                999,
                                3,
                                PermissionGrant.Effect.ALLOW,
                                false,
                                null),
                        grant(
                                7,
                                SubjectType.USER,
                                101,
                                3,
                                PermissionGrant.Effect.ALLOW,
                                false,
                                null),
                        grant(
                                8,
                                SubjectType.DEPT,
                                301,
                                3,
                                PermissionGrant.Effect.ALLOW,
                                false,
                                null));

        assertTrue(GrantExpander.expand(grants, SUBJECT, TREE, 3).isEmpty());
    }

    @Test
    void expiredRowsAreReturnedForTheEvaluatorToReject() {
        GrantRow expired =
                grant(
                        9,
                        SubjectType.USER,
                        100,
                        3,
                        PermissionGrant.Effect.ALLOW,
                        false,
                        NOW.minusSeconds(1));

        List<PermissionGrant> expanded = GrantExpander.expand(List.of(expired), SUBJECT, TREE, 3);

        assertEquals(1, expanded.size());
        assertFalse(PermissionEvaluator.evaluate(expanded, true, true, NOW).read());
    }

    @Test
    void duplicateGrantIdsAreReturnedOnce() {
        GrantRow first =
                grant(10, SubjectType.USER, 100, 3, PermissionGrant.Effect.ALLOW, false, null);
        GrantRow duplicate =
                grant(10, SubjectType.USER, 100, 3, PermissionGrant.Effect.DENY, false, null);

        List<PermissionGrant> expanded =
                GrantExpander.expand(List.of(first, duplicate), SUBJECT, TREE, 3);

        assertEquals(1, expanded.size());
        assertEquals(PermissionGrant.Effect.ALLOW, expanded.get(0).effect());
    }

    private static GrantRow grant(
            long grantId,
            SubjectType subjectType,
            long subjectId,
            long resourceId,
            PermissionGrant.Effect effect,
            boolean includeDescendants,
            Instant validTo) {
        return new GrantRow(
                subjectType,
                subjectId,
                resourceId,
                PermissionGrant.Action.READ,
                effect,
                includeDescendants,
                NOW.minusSeconds(3600),
                validTo,
                grantId);
    }
}
