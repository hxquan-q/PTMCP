package local.ptmcp.datasource.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.resource.DatabaseEngine;
import local.ptmcp.datasource.PoolKey;
import local.ptmcp.datasource.PoolLimits;
import local.ptmcp.datasource.testing.TlsDatabaseFixture;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

/** The management and pooled paths use real sockets and CA-signed certificates for both engines. */
class PhysicalConnectionMatrixTest {
    private static TlsDatabaseFixture mysql;
    private static TlsDatabaseFixture pg;

    @BeforeAll
    static void start() throws Exception {
        mysql = new TlsDatabaseFixture(DatabaseEngine.MYSQL);
        pg = new TlsDatabaseFixture(DatabaseEngine.POSTGRESQL);
    }

    @AfterAll
    static void stop() throws Exception {
        if (pg != null) {
            pg.close();
        }
        if (mysql != null) {
            mysql.close();
        }
    }

    private static PhysicalConnectionFactory factory(
            DestinationPolicy policy, TlsDatabaseFixture trust) {
        return new PhysicalConnectionFactory(
                policy,
                trust.trustStore.toString(),
                trust.password,
                "PKCS12",
                trust.rootCert.toString());
    }

    private static ConnectionProfile profile(TlsDatabaseFixture fixture, String host) {
        return new ConnectionProfile(
                fixture.engine,
                host,
                fixture.port(),
                TlsDatabaseFixture.DATABASE,
                TlsDatabaseFixture.USER);
    }

    private static DestinationPolicy policy(TlsDatabaseFixture fixture) {
        return new DestinationPolicy("127.0.0.0/8", Integer.toString(fixture.port()));
    }

    private static void assertTls(Connection connection, DatabaseEngine engine) throws Exception {
        try (var statement = connection.createStatement();
                var result =
                        statement.executeQuery(
                                engine == DatabaseEngine.POSTGRESQL
                                        ? "SELECT ssl FROM pg_stat_ssl WHERE pid=pg_backend_pid()"
                                        : "SHOW SESSION STATUS LIKE 'Ssl_cipher'")) {
            assertTrue(result.next());
            if (engine == DatabaseEngine.POSTGRESQL) {
                assertTrue(result.getBoolean(1));
            } else {
                assertFalse(result.getString(2).isBlank());
            }
        }
    }

    @Test
    void trustedManagementAndQueryConnectionsUseTlsWithoutChangingGlobalTimeout() throws Exception {
        int loginTimeout = DriverManager.getLoginTimeout();
        for (TlsDatabaseFixture fixture : List.of(mysql, pg)) {
            PhysicalConnectionFactory factory = factory(policy(fixture), fixture);
            try (Connection connection =
                    factory.openManagement(
                            profile(fixture, "127.0.0.1"), fixture.password.toCharArray())) {
                assertTrue(connection.isReadOnly());
                assertTls(connection, fixture.engine);
            }
            PoolKey key =
                    new PoolKey(
                            1,
                            1,
                            fixture.engine,
                            "127.0.0.1",
                            fixture.port(),
                            TlsDatabaseFixture.DATABASE,
                            TlsDatabaseFixture.USER,
                            1);
            DataSource pool =
                    factory.createPool(key, fixture.password.toCharArray(), PoolLimits.of(1));
            try (AutoCloseable close = (AutoCloseable) pool;
                    Connection connection = pool.getConnection()) {
                assertTls(connection, fixture.engine);
            }
        }
        assertEquals(loginTimeout, DriverManager.getLoginTimeout());
        assertThrows(IOException.class, PinnedTarget::require);
    }

    @Test
    void wrongCaAndMissingTrustFailClosedForBothEngines() throws Exception {
        for (TlsDatabaseFixture fixture : List.of(mysql, pg)) {
            TlsDatabaseFixture wrong = fixture == mysql ? pg : mysql;
            assertThrows(
                    SQLException.class,
                    () ->
                            factory(policy(fixture), wrong)
                                    .openManagement(
                                            profile(fixture, "127.0.0.1"),
                                            fixture.password.toCharArray()));
            PhysicalConnectionFactory missing =
                    new PhysicalConnectionFactory(policy(fixture), "", "", "PKCS12", "");
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            missing.openManagement(
                                    profile(fixture, "127.0.0.1"), fixture.password.toCharArray()));
        }
        assertThrows(IOException.class, PinnedTarget::require);
    }

    @Test
    void originalHostnameStillControlsTlsWhenDnsIsPinned() throws Exception {
        for (TlsDatabaseFixture fixture : List.of(mysql, pg)) {
            DestinationPolicy pinned =
                    new DestinationPolicy(
                            "127.0.0.0/8",
                            Integer.toString(fixture.port()),
                            host -> new InetAddress[] {InetAddress.getByName("127.0.0.1")});
            // This name has no public DNS record. The approved socket uses its address, while
            // the certificate contains the original hostname and verify-full must still check it.
            try (Connection connection =
                    factory(pinned, fixture)
                            .openManagement(
                                    profile(fixture, "db.synthetic.invalid"),
                                    fixture.password.toCharArray())) {
                assertTls(connection, fixture.engine);
            }
            assertThrows(
                    SQLException.class,
                    () ->
                            factory(pinned, fixture)
                                    .openManagement(
                                            profile(fixture, "wrong.synthetic.invalid"),
                                            fixture.password.toCharArray()));
        }
    }

    @Test
    void changedDnsAnswersAreRecheckedAtEachPhysicalCheckout() throws Exception {
        for (TlsDatabaseFixture fixture : List.of(mysql, pg)) {
            AtomicBoolean allowed = new AtomicBoolean(true);
            AtomicInteger resolutions = new AtomicInteger();
            DestinationPolicy policy =
                    new DestinationPolicy(
                            "127.0.0.0/8",
                            Integer.toString(fixture.port()),
                            host -> {
                                resolutions.incrementAndGet();
                                return new InetAddress[] {
                                    InetAddress.getByName(allowed.get() ? "127.0.0.1" : "192.0.2.1")
                                };
                            });
            PhysicalConnectionFactory factory = factory(policy, fixture);
            PoolKey key =
                    new PoolKey(
                            1,
                            1,
                            fixture.engine,
                            "127.0.0.1",
                            fixture.port(),
                            TlsDatabaseFixture.DATABASE,
                            TlsDatabaseFixture.USER,
                            1);
            DataSource pool =
                    factory.createPool(key, fixture.password.toCharArray(), PoolLimits.of(1));
            allowed.set(false);
            try (AutoCloseable close = (AutoCloseable) pool) {
                assertThrows(SQLException.class, pool::getConnection);
                assertTrue(resolutions.get() >= 2);
                assertThrows(
                        DomainException.class,
                        () ->
                                factory.openManagement(
                                        profile(fixture, "127.0.0.1"),
                                        fixture.password.toCharArray()));
            }
        }
    }

    @Test
    void expiredCertificatesAreRejectedByBothDrivers() throws Exception {
        for (DatabaseEngine engine : DatabaseEngine.values()) {
            try (TlsDatabaseFixture expired =
                    new TlsDatabaseFixture(engine, "SAN=dns:localhost,ip:127.0.0.1", true)) {
                assertThrows(
                        SQLException.class,
                        () ->
                                factory(policy(expired), expired)
                                        .openManagement(
                                                profile(expired, "127.0.0.1"),
                                                expired.password.toCharArray()));
            }
        }
    }
}
