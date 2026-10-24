package local.ptmcp.persistence.schema;

import java.sql.Connection;

/**
 * Captures one normalized snapshot over an open metadata connection. Implementations run only
 * built-in, parameterized catalog queries — never caller-supplied SQL — and enforce the {@link
 * SnapshotModel} bounds.
 */
@FunctionalInterface
public interface MetadataCollector {

    SnapshotModel collect(Connection connection);
}
