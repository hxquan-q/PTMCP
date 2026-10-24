package local.ptmcp.guard.approved;

import local.ptmcp.common.resource.ResourceRef;

/**
 * Authorization callback over the physical relations bound by the pipeline. Returns the caller's
 * read decision for one exact {@link ResourceRef} (name, object identity, generation and versions
 * all matter); the pipeline consults it only after binding and rejects as {@code
 * TABLE_NOT_AUTHORIZED} on the first denial. Deciding is the caller's duty — the guard never
 * queries an authorization store itself.
 */
@FunctionalInterface
public interface PermissionDecider {
    boolean canRead(GuardContext context, ResourceRef resource);
}
