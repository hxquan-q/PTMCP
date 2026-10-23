package local.ptmcp.common.resource;

import java.util.Objects;

/**
 * An exact physical name within a complete namespace, selected from a published server-side
 * snapshot. Equality includes the datasource, engine, catalog, schema and unchanged object name.
 * The object name must contain 1 to 128 Unicode codepoints, without NUL or malformed UTF-16; no
 * trimming, case folding or Unicode normalization is performed.
 *
 * <p>This key carries no permission, object generation or snapshot version; equal names do not
 * justify inheriting authorization after a drop/recreate. The object name is data, not an
 * executable SQL fragment, and requires dialect-aware quoting before any SQL rendering.
 */
public record ResourceKey(ResourceNamespace namespace, String objectName) {
    public ResourceKey {
        Objects.requireNonNull(namespace, "namespace");
        IdentifierNames.requireValid(objectName, "objectName");
    }
}
