package local.ptmcp.gateway.mcp;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import local.ptmcp.datasource.ApprovedQueryAdapter;
import local.ptmcp.datasource.DataSourcePoolRegistry;
import local.ptmcp.datasource.ExecutorSettings;
import local.ptmcp.datasource.MysqlReadOnlyExecutor;
import local.ptmcp.datasource.connection.CredentialCipher;
import local.ptmcp.datasource.connection.DestinationPolicy;
import local.ptmcp.datasource.connection.PinnedTlsMysqlPoolFactory;
import local.ptmcp.datasource.connection.PinnedTlsPgPoolFactory;
import local.ptmcp.datasource.connection.PinnedTlsPoolFactory;
import local.ptmcp.datasource.postgres.PgApprovedQueryAdapter;
import local.ptmcp.guard.budget.BoundedParser;
import local.ptmcp.persistence.audit.AuditRepository;
import local.ptmcp.persistence.audit.wal.TerminalWal;
import local.ptmcp.persistence.audit.wal.WalKey;
import local.ptmcp.persistence.audit.wal.WalKeyProvider;
import local.ptmcp.persistence.org.AuthorizationReader;
import local.ptmcp.persistence.schema.PublishedSnapshotReader;
import local.ptmcp.persistence.security.SecurityStateReader;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.net.InetAddress;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import javax.sql.DataSource;

/**
 * Wires the MCP boundary only when the deployment enables it: primary metadata access, the
 * authoritative security-state reader, durable audit with the local encrypted WAL fallback, the
 * published-snapshot and authorization readers, the pinned TLS business pools and the JSON-RPC tool
 * service. Missing mandatory configuration fails startup instead of degrading security.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayProperties.class)
@ConditionalOnProperty(prefix = "ptmcp.gateway", name = "enabled", havingValue = "true")
class McpGatewayConfiguration {

    @Bean(destroyMethod = "close")
    DataSource primaryMetadata(GatewayProperties properties) {
        if (properties.metadataUrl().isBlank()) {
            throw new IllegalStateException("ptmcp.gateway.metadata-url is required");
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(properties.metadataUrl());
        config.setUsername(properties.metadataUsername());
        config.setPassword(properties.metadataPassword());
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(5_000);
        config.setValidationTimeout(2_000);
        config.setPoolName("ptmcp-metadata-primary");
        config.setAutoCommit(true);
        config.setRegisterMbeans(false);
        // Lazy start: an unreachable primary must not kill the process at boot; every reader and
        // writer already fails closed with its registered code when the pool cannot serve.
        config.setInitializationFailTimeout(-1);
        return new HikariDataSource(config);
    }

    @Bean
    SecurityStateReader securityStateReader(DataSource primaryMetadata) {
        return new SecurityStateReader(primaryMetadata);
    }

    @Bean
    AuditRepository auditRepository(DataSource primaryMetadata) {
        return new AuditRepository(primaryMetadata);
    }

    @Bean(destroyMethod = "close")
    TerminalWal terminalWal(GatewayProperties properties) {
        if (properties.walDirectory().isBlank() || properties.walKey().isBlank()) {
            throw new IllegalStateException(
                    "ptmcp.gateway.wal-directory and wal-key are required when enabled");
        }
        return new TerminalWal(
                java.nio.file.Path.of(properties.walDirectory()),
                instanceId(),
                UUID.randomUUID().toString(),
                walKeyProvider(properties));
    }

    @Bean
    WalKeyProvider walKeyProvider(GatewayProperties properties) {
        byte[] raw = Base64.getDecoder().decode(properties.walKey());
        if (raw.length != 32) {
            throw new IllegalStateException("ptmcp.gateway.wal-key must decode to 32 bytes");
        }
        String keyId = "local:" + HexFormat.of().formatHex(sha256(raw)).substring(0, 16);
        WalKey key = new WalKey(keyId, raw);
        return () -> key;
    }

    @Bean
    PublishedSnapshotReader publishedSnapshotReader(DataSource primaryMetadata) {
        return new PublishedSnapshotReader(primaryMetadata);
    }

    @Bean
    AuthorizationReader authorizationReader(DataSource primaryMetadata) {
        return new AuthorizationReader(primaryMetadata);
    }

    @Bean
    CredentialCipher credentialCipher(GatewayProperties properties) {
        return new CredentialCipher(properties.credentialKey());
    }

    @Bean
    DestinationPolicy destinationPolicy(GatewayProperties properties) {
        return new DestinationPolicy(properties.allowedCidrs());
    }

    @Bean(destroyMethod = "close")
    DataSourcePoolRegistry businessPoolRegistry(
            DestinationPolicy destinations, GatewayProperties properties) {
        return new DataSourcePoolRegistry(
                new PinnedTlsPoolFactory(
                        new PinnedTlsMysqlPoolFactory(
                                destinations,
                                properties.truststorePath(),
                                properties.truststorePassword(),
                                "PKCS12"),
                        new PinnedTlsPgPoolFactory(destinations, properties.pgSslRootCert())));
    }

    @Bean
    MysqlReadOnlyExecutor mysqlReadOnlyExecutor(DataSourcePoolRegistry registry) {
        return new MysqlReadOnlyExecutor(registry, ExecutorSettings.defaults());
    }

    @Bean
    ApprovedQueryAdapter approvedQueryAdapter(MysqlReadOnlyExecutor executor) {
        return new ApprovedQueryAdapter(executor);
    }

    @Bean
    PgApprovedQueryAdapter pgApprovedQueryAdapter() {
        return new PgApprovedQueryAdapter();
    }

    @Bean(destroyMethod = "close")
    BoundedParser boundedParser() {
        return new BoundedParser(Duration.ofMillis(500));
    }

    @Bean
    McpToolService mcpToolService(
            SecurityStateReader security,
            AuditRepository auditRepository,
            PublishedSnapshotReader snapshots,
            AuthorizationReader authorization,
            CredentialCipher cipher,
            DataSourcePoolRegistry poolRegistry,
            ApprovedQueryAdapter adapter,
            PgApprovedQueryAdapter pgAdapter,
            BoundedParser parser,
            McpAuthentication authentication,
            ObjectProvider<TerminalWal> terminalWal) {
        return new McpToolService(
                security,
                auditRepository,
                terminalWal.getIfAvailable(),
                snapshots,
                authorization,
                cipher,
                poolRegistry,
                adapter,
                pgAdapter,
                parser,
                authentication);
    }

    @Bean
    Tier1ProofVerifier tier1ProofVerifier(
            AuthorizationReader authorization, CredentialCipher cipher) {
        return new Tier1ProofVerifier(authorization, cipher);
    }

    @Bean
    McpAuthentication mcpAuthentication(
            GatewayProperties properties,
            DataSource primaryMetadata,
            SecurityStateReader security,
            Tier1ProofVerifier proofVerifier) {
        JwtDecoder jwtDecoder = null;
        if (!properties.jwksUri().isBlank()) {
            if (properties.issuer().isBlank() || properties.resource().isBlank()) {
                throw new IllegalStateException(
                        "ptmcp.gateway.issuer and resource are required with jwks-uri");
            }
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwksUri()).build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
            jwtDecoder = decoder;
        }
        return new PatJwtAuthentication(
                new IdentityStore(primaryMetadata),
                security,
                proofVerifier,
                jwtDecoder,
                properties.resource());
    }

    private static String instanceId() {
        try {
            String host = InetAddress.getLocalHost().getHostName();
            return host.length() > 128 ? host.substring(0, 128) : host;
        } catch (java.io.IOException error) {
            return "ptmcp-gateway-" + UUID.randomUUID();
        }
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
