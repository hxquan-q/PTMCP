package local.ptmcp.admin.datasource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import local.ptmcp.common.resource.DatabaseEngine;
import local.ptmcp.datasource.connection.DestinationPolicy;
import local.ptmcp.datasource.testing.TlsDatabaseFixture;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Base64;

/** Real admin decryption + shared TLS transport for the same factory used by TEST and SYNC. */
class DatasourceConnectionFactoryTest {
    @Test
    void bothEnginesUseSeparateTrustMaterialAndKeepTheGlobalTimeout() throws Exception {
        CredentialCipher cipher =
                new CredentialCipher(Base64.getEncoder().encodeToString(new byte[32]));
        int globalTimeout = DriverManager.getLoginTimeout();
        for (DatabaseEngine engine : DatabaseEngine.values()) {
            try (TlsDatabaseFixture fixture = new TlsDatabaseFixture(engine)) {
                CredentialCipher.Sealed sealed = cipher.encrypt(fixture.password);
                DestinationPolicy policy =
                        new DestinationPolicy("127.0.0.0/8", Integer.toString(fixture.port()));
                DatasourceConnectionFactory factory =
                        new DatasourceConnectionFactory(
                                policy,
                                cipher,
                                engine == DatabaseEngine.MYSQL
                                        ? fixture.trustStore.toString()
                                        : "unused-mysql-path",
                                fixture.password,
                                "PKCS12",
                                engine == DatabaseEngine.POSTGRESQL
                                        ? fixture.rootCert.toString()
                                        : "unused-pg-path");
                DatasourceConnectionFactory.Spec spec =
                        new DatasourceConnectionFactory.Spec(
                                engine.name(),
                                "127.0.0.1",
                                fixture.port(),
                                TlsDatabaseFixture.DATABASE,
                                TlsDatabaseFixture.USER,
                                sealed.ciphertext(),
                                sealed.keyId());
                try (Connection connection = factory.open(spec)) {
                    assertTrue(connection.isReadOnly());
                    assertTrue(connection.isValid(2));
                    try (var statement = connection.createStatement();
                            var result =
                                    statement.executeQuery(
                                            engine == DatabaseEngine.MYSQL
                                                    ? "SHOW SESSION STATUS LIKE 'Ssl_cipher'"
                                                    : "SELECT ssl FROM pg_stat_ssl WHERE pid=pg_backend_pid()")) {
                        assertTrue(result.next());
                        assertTrue(
                                engine == DatabaseEngine.MYSQL
                                        ? !result.getString(2).isBlank()
                                        : result.getBoolean(1));
                    }
                }
                DatasourceConnectionFactory missing =
                        new DatasourceConnectionFactory(policy, cipher, "", "", "PKCS12", "");
                assertThrows(IllegalStateException.class, () -> missing.open(spec));
            }
        }
        assertEquals(globalTimeout, DriverManager.getLoginTimeout());
    }
}
