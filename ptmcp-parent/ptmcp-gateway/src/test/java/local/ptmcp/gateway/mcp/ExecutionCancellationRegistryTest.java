package local.ptmcp.gateway.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import local.ptmcp.datasource.ReadOnlyCancellation;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

/** Subject-scoped cancel addressing: the boundary's only path from a notification to live work. */
class ExecutionCancellationRegistryTest {
    private static final McpIdentity IDENTITY = identity(1, 1, "pat:1");

    @Test
    void cancelReachesOnlyTheOwningSubjectAndRpcId() {
        ExecutionCancellationRegistry registry = new ExecutionCancellationRegistry();
        ReadOnlyCancellation mine = new ReadOnlyCancellation();
        ReadOnlyCancellation other = new ReadOnlyCancellation();
        ReadOnlyCancellation otherId = new ReadOnlyCancellation();
        registry.register(ExecutionCancellationRegistry.Key.of(IDENTITY, "7"), mine);
        registry.register(
                ExecutionCancellationRegistry.Key.of(identity(2, 1, "pat:2"), "7"), other);
        registry.register(
                ExecutionCancellationRegistry.Key.of(identity(1, 2, "pat:1"), "7"), other);
        registry.register(ExecutionCancellationRegistry.Key.of(IDENTITY, "8"), otherId);

        assertTrue(registry.cancel(ExecutionCancellationRegistry.Key.of(IDENTITY, "7")));
        assertTrue(mine.isCancelled());
        assertFalse(other.isCancelled());
        assertFalse(otherId.isCancelled());
        assertEquals(4, registry.size());
    }

    @Test
    void unknownIdIsIgnoredAndUnregisterOnlyRemovesOwnsHandle() {
        ExecutionCancellationRegistry registry = new ExecutionCancellationRegistry();
        ReadOnlyCancellation first = new ReadOnlyCancellation();
        ReadOnlyCancellation second = new ReadOnlyCancellation();
        ExecutionCancellationRegistry.Key key = ExecutionCancellationRegistry.Key.of(IDENTITY, "7");

        assertFalse(registry.cancel(key));

        registry.register(key, first);
        registry.unregister(key, second);
        assertEquals(1, registry.size(), "a stale unregister must not drop the live handle");
        registry.unregister(key, first);
        assertEquals(0, registry.size());
        assertFalse(registry.cancel(key));
    }

    @Test
    void cancelObservedWhileQueuedReachesTheHandleAndIsDroppedAfterUnregister() {
        ExecutionCancellationRegistry registry = new ExecutionCancellationRegistry();
        ReadOnlyCancellation handle = new ReadOnlyCancellation();
        ExecutionCancellationRegistry.Key key = ExecutionCancellationRegistry.Key.of(IDENTITY, "7");
        registry.register(key, handle);

        assertTrue(registry.cancel(key));
        assertTrue(handle.isCancelled(), "the queued wait observes the cancel at its next check");

        registry.unregister(key, handle);
        assertEquals(0, registry.size());
        assertFalse(registry.cancel(key));
    }

    private static McpIdentity identity(long userId, long platformId, String credentialId) {
        return new McpIdentity(
                userId,
                platformId,
                1,
                credentialId,
                "client-1",
                Set.of("mcp:query"),
                Instant.now().plusSeconds(300),
                1,
                Instant.now());
    }
}
