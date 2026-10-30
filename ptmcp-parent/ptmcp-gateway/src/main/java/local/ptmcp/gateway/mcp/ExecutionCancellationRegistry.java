package local.ptmcp.gateway.mcp;

import local.ptmcp.datasource.ReadOnlyCancellation;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-process owner table from one authenticated request to the cancellation handle its executor is
 * using. Keys are subject-scoped (user, platform and credential) plus the JSON-RPC id the client
 * chose, because that id is only unique inside one credential's own stream; a global id map would
 * let another caller cancel someone else's query. Entries exist only while a request is inside the
 * admission or execution path, so a cancel for an unknown or already finished id is ignored, which
 * is what the protocol requires.
 *
 * <p>shortcut: a cancel that arrives after this credential's own request unregistered and before a
 * later request reuses the same id can still hit the newer execution; both belong to the same
 * credential, so it can only cancel work it may cancel anyway. Upgrade with a per-registration
 * generation token if a client ever needs that window closed.
 */
final class ExecutionCancellationRegistry {
    /** Client-visible request identity, scoped to the subject and credential that sent it. */
    record Key(long userId, long platformId, String credentialId, String rpcId) {
        static Key of(McpIdentity identity, String rpcId) {
            return new Key(
                    identity.userId(), identity.platformId(), identity.credentialId(), rpcId);
        }
    }

    private final ConcurrentMap<Key, ReadOnlyCancellation> handles = new ConcurrentHashMap<>();

    void register(Key key, ReadOnlyCancellation handle) {
        handles.put(key, handle);
    }

    /**
     * Removes only the handle this request registered, so a late unregister cannot drop a new one.
     */
    void unregister(Key key, ReadOnlyCancellation handle) {
        handles.remove(key, handle);
    }

    /** True when a live execution for this subject and id was signalled. */
    boolean cancel(Key key) {
        ReadOnlyCancellation handle = handles.get(key);
        if (handle == null) {
            return false;
        }
        handle.cancel();
        return true;
    }

    int size() {
        return handles.size();
    }
}
