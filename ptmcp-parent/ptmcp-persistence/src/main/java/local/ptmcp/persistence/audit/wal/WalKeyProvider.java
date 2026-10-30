package local.ptmcp.persistence.audit.wal;

import java.util.Optional;

/**
 * Supplies the current WAL data key. Production implementations bind to a KMS; this slice ships
 * only in-memory test providers. A key that cannot be retrieved must abort the append — no
 * plaintext fallback is allowed.
 */
@FunctionalInterface
public interface WalKeyProvider {
    WalKey current();

    /**
     * Key that sealed a frame carrying {@code keyId}. Rotation keeps retired entries here so
     * segments written with the old key still replay; a provider with a single key answers only for
     * that key.
     */
    default Optional<WalKey> byId(String keyId) {
        WalKey key = current();
        return key.keyId().equals(keyId) ? Optional.of(key) : Optional.empty();
    }
}
