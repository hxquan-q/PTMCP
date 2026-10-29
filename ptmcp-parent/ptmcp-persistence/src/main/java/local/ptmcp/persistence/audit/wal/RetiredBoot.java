package local.ptmcp.persistence.audit.wal;

import local.ptmcp.persistence.audit.ExecutionOwner;

/** Proof issued only while holding the old process's persistent directory lock. */
public final class RetiredBoot {
    private final WalDirectoryOwner owner;
    private final ExecutionOwner identity;

    RetiredBoot(WalDirectoryOwner owner, ExecutionOwner identity) {
        this.owner = owner;
        this.identity = identity;
    }

    public ExecutionOwner identity() {
        requireHeld();
        return identity;
    }

    public void requireHeld() {
        owner.requireHeld();
    }
}
