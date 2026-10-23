package local.ptmcp.common.resource;

import java.util.Objects;

/**
 * A physical resource key together with caller-supplied object identity, generation and versions.
 * Generation, object version and snapshot version must each be positive; equality includes every
 * component, so a name alone cannot identify a rebuilt object or a different version.
 *
 * <p>This value does not verify snapshot freshness, establish a trusted context or decide
 * permission. An equal name never justifies inheriting authorization after a drop/recreate. HTTP
 * adapters must explicitly map domain data rather than directly bind or serialize this record.
 */
public record ResourceRef(
        ResourceKey key,
        ResourceId objectId,
        long generation,
        long objectVersion,
        long snapshotVersion) {
    public ResourceRef {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(objectId, "objectId");
        if (generation <= 0 || objectVersion <= 0 || snapshotVersion <= 0) {
            throw new IllegalArgumentException("Resource generation and versions must be positive");
        }
    }
}
