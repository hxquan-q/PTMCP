package local.ptmcp.guard.approved;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import local.ptmcp.common.resource.DatabaseEngine;
import local.ptmcp.common.resource.ResourceId;
import local.ptmcp.common.resource.ResourceKey;
import local.ptmcp.common.resource.ResourceNamespace;
import local.ptmcp.common.resource.ResourceRef;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.List;

/** Tamper resistance of the approved-query envelope: no public construction, no mutation. */
class ApprovedQueryTest {
    private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");

    @Test
    void hasNoPublicConstructorAndNoSetters() {
        assertEquals(0, ApprovedQuery.class.getConstructors().length);
        for (Constructor<?> constructor : ApprovedQuery.class.getDeclaredConstructors()) {
            assertTrue(
                    !Modifier.isPublic(constructor.getModifiers()),
                    "constructor must not be public: " + constructor);
        }
        for (Method method : ApprovedQuery.class.getMethods()) {
            assertTrue(
                    !method.getName().startsWith("set"),
                    "setter must not exist: " + method.getName());
        }
    }

    @Test
    void contextMirrorsConstructorCallerSuppliedValues() {
        GuardContext context = context();
        ApprovedQuery approved =
                new ApprovedQuery(
                        java.util.UUID.nameUUIDFromBytes(new byte[0]),
                        context,
                        List.of(ref()),
                        "SELECT 1",
                        new byte[] {1, 2},
                        500,
                        "1",
                        NOW);
        assertEquals(9, approved.principalId());
        assertEquals(5, approved.platformId());
        assertEquals(new ResourceId(7), approved.datasourceId());
        assertEquals("shop", approved.catalog());
        assertEquals("", approved.schema());
        assertEquals(new ResourceId(11), approved.snapshotId());
        assertEquals(4, approved.resourceVersion());
        assertEquals(1, approved.policyEpoch());
        assertEquals(2, approved.authVersion());
        assertEquals(NOW, approved.approvedAt());
        assertEquals(NOW.plusSeconds(10), approved.expiresAt());
    }

    @Test
    void sqlHashAndResourceRefsAreDefensivelyCopied() {
        byte[] hash = new byte[] {1, 2, 3};
        List<ResourceRef> refs = List.of(ref());
        ApprovedQuery approved =
                new ApprovedQuery(
                        java.util.UUID.nameUUIDFromBytes(new byte[0]),
                        context(),
                        refs,
                        "SELECT 1",
                        hash,
                        500,
                        "1",
                        NOW);
        approved.sqlHash()[0] = 99;
        assertArrayEquals(new byte[] {1, 2, 3}, approved.sqlHash());
        hash[0] = 99;
        assertArrayEquals(new byte[] {1, 2, 3}, approved.sqlHash());
        assertThrows(UnsupportedOperationException.class, () -> approved.resourceRefs().add(ref()));
    }

    @Test
    void expiryFollowsTheApprovalWindow() {
        ApprovedQuery approved =
                new ApprovedQuery(
                        java.util.UUID.nameUUIDFromBytes(new byte[0]),
                        context(),
                        List.of(ref()),
                        "SELECT 1",
                        new byte[] {1},
                        500,
                        "1",
                        NOW);
        assertTrue(approved.isUsable(NOW));
        assertTrue(approved.isUsable(NOW.plusSeconds(10)));
        assertFalse(approved.isUsable(NOW.plusSeconds(11)));
    }

    private static GuardContext context() {
        return new GuardContext(
                9,
                5,
                new ResourceId(7),
                3,
                "shop",
                "",
                new ResourceId(11),
                4,
                1,
                2,
                Instant.parse("2026-10-23T10:00:00Z"));
    }

    private static ResourceRef ref() {
        ResourceNamespace namespace =
                new ResourceNamespace(new ResourceId(7), DatabaseEngine.MYSQL, "shop", "");
        return new ResourceRef(new ResourceKey(namespace, "users"), new ResourceId(1), 1, 1, 1);
    }
}
