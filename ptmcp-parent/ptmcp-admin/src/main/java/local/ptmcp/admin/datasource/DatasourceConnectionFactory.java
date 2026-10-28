package local.ptmcp.admin.datasource;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.common.resource.DatabaseEngine;
import local.ptmcp.datasource.connection.ConnectionProfile;
import local.ptmcp.datasource.connection.DestinationPolicy;
import local.ptmcp.datasource.connection.PhysicalConnectionFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Objects;

/** Decrypts only for a server-owned, DNS-pinned TEST/SYNC connection. No arbitrary JDBC options. */
public final class DatasourceConnectionFactory {
    private final DestinationPolicy destinations;
    private final CredentialCipher cipher;
    private final PhysicalConnectionFactory physical;

    public DatasourceConnectionFactory(
            DestinationPolicy destinations,
            CredentialCipher cipher,
            String mysqlTruststorePath,
            String mysqlTruststorePassword,
            String mysqlTruststoreType,
            String pgRootCert) {
        this.destinations = Objects.requireNonNull(destinations, "destinations");
        this.cipher = Objects.requireNonNull(cipher, "cipher");
        this.physical =
                new PhysicalConnectionFactory(
                        destinations,
                        mysqlTruststorePath,
                        mysqlTruststorePassword,
                        mysqlTruststoreType,
                        pgRootCert);
    }

    public record Spec(
            String engine,
            String host,
            int port,
            String databaseName,
            String username,
            byte[] credentialCipher,
            String keyId) {}

    public Connection open(Spec spec) throws SQLException {
        if (spec == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        DatabaseEngine engine;
        try {
            engine = DatabaseEngine.valueOf(spec.engine());
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        ConnectionProfile profile =
                new ConnectionProfile(
                        engine, spec.host(), spec.port(), spec.databaseName(), spec.username());
        destinations.validate(profile.host(), profile.port());
        char[] credential = cipher.decrypt(spec.credentialCipher(), spec.keyId()).toCharArray();
        try {
            return physical.openManagement(profile, credential);
        } finally {
            Arrays.fill(credential, '\0');
        }
    }

    public void validateDestination(String host, int port) {
        destinations.validate(host, port);
    }
}
