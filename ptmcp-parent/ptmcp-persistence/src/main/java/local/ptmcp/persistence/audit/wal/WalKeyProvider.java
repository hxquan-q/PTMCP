package local.ptmcp.persistence.audit.wal;

/**
 * Supplies the current WAL data key. Production implementations bind to a KMS; this slice ships
 * only in-memory test providers. A key that cannot be retrieved must abort the append — no
 * plaintext fallback is allowed.
 */
@FunctionalInterface
public interface WalKeyProvider {
    WalKey current();
}
