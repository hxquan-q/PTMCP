package local.ptmcp.datasource.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import local.ptmcp.common.resource.DatabaseEngine;
import local.ptmcp.datasource.PoolKey;
import local.ptmcp.datasource.PoolLimits;
import local.ptmcp.datasource.testing.TlsDatabaseFixture;

import org.junit.jupiter.api.Test;

import java.sql.Connection;

import javax.sql.DataSource;

class PinnedTlsConnectionTest {
    @Test
    void postgresProductionFactoryUsesVerifiedTlsAndPinnedSocket() throws Exception {
        try (TlsDatabaseFixture fixture = new TlsDatabaseFixture(DatabaseEngine.POSTGRESQL)) {
            DestinationPolicy destinations =
                    new DestinationPolicy("127.0.0.0/8", Integer.toString(fixture.port()));
            PoolKey key =
                    new PoolKey(
                            1,
                            1,
                            DatabaseEngine.POSTGRESQL,
                            "127.0.0.1",
                            fixture.port(),
                            TlsDatabaseFixture.DATABASE,
                            TlsDatabaseFixture.USER,
                            1);
            DataSource source =
                    new PinnedTlsPgPoolFactory(destinations, fixture.rootCert.toString())
                            .create(key, fixture.password.toCharArray(), PoolLimits.of(1));
            try (AutoCloseable close = (AutoCloseable) source;
                    Connection connection = source.getConnection();
                    var statement = connection.createStatement();
                    var result =
                            statement.executeQuery(
                                    "SELECT ssl FROM pg_stat_ssl WHERE pid = pg_backend_pid()")) {
                assertTrue(result.next());
                assertTrue(result.getBoolean(1));
                assertEquals(true, connection.isValid(2));
            }
        }
    }
}
