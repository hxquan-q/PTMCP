package local.ptmcp.common.resource;

import java.util.Objects;

/**
 * A physical namespace selected from a published server-side snapshot, not a verified or authorized
 * execution context. Catalog and schema are exact snapshot names; database-specific resolution and
 * case rules belong to the snapshot producer and binder, not this value.
 *
 * <p>MySQL uses the database as catalog and exactly the empty string as schema. PostgreSQL requires
 * both catalog and schema. Names are preserved without trimming, case folding or Unicode
 * normalization, and are not SQL fragments or safe-to-concatenate SQL identifiers. Catalog and
 * PostgreSQL schema must each contain 1 to 128 Unicode codepoints, without NUL or malformed UTF-16.
 */
public record ResourceNamespace(
        ResourceId datasourceId, DatabaseEngine engine, String catalog, String schema) {
    public ResourceNamespace {
        Objects.requireNonNull(datasourceId, "datasourceId");
        Objects.requireNonNull(engine, "engine");
        IdentifierNames.requireValid(catalog, "catalog");
        switch (engine) {
            case MYSQL -> {
                if (!"".equals(schema)) {
                    throw new IllegalArgumentException("MySQL schema must be the empty string");
                }
            }
            case POSTGRESQL -> IdentifierNames.requireValid(schema, "schema");
        }
    }
}
