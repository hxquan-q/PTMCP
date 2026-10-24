package local.ptmcp.persistence.schema;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Opens a bounded metadata connection for one datasource; the implementer owns TLS/credential
 * wiring.
 */
@FunctionalInterface
public interface MetadataConnectionOpener {

    Connection open(long datasourceId) throws SQLException;
}
