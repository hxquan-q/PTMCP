package local.ptmcp.common.authz;

/** One node of the authoritative resource tree; {@code parentId} is null for a root. */
public record ResourceNode(long id, Long parentId) {
    public ResourceNode {
        SubjectScope.requirePositive(id, "id");
        if (parentId != null) {
            SubjectScope.requirePositive(parentId, "parentId");
            if (parentId == id) {
                throw new IllegalArgumentException("resource cannot be its own parent");
            }
        }
    }
}
