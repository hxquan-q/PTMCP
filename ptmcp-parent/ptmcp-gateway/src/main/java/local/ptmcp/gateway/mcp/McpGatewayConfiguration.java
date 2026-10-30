package local.ptmcp.gateway.mcp;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import local.ptmcp.common.crypto.SealedEnvelope;
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
import local.ptmcp.persistence.security.InMemoryQuotaLimiter;
import local.ptmcp.persistence.security.QuotaLimiter;
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
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
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
    AuditRecoveryWorker auditRecoveryWorker(
            AuditRepository repository, TerminalWal wal, GatewayProperties properties) {
        AuditRecoveryWorker worker =
                new AuditRecoveryWorker(
                        repository, wal, properties.walMaxBytes(), properties.walMinFreeBytes());
        worker.start();
        return worker;
    }

    @Bean
    WalKeyProvider walKeyProvider(GatewayProperties properties) {
        return new WalKeyRing(properties.walKey());
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

    /** Disabled unless ptmcp.gateway.audit-key is set; terminals keep null ciphers then. */
    @Bean
    AuditSealer auditSealer(GatewayProperties properties) {
        return new AuditSealer(properties.auditKey());
    }

    @Bean
    DestinationPolicy destinationPolicy(GatewayProperties properties) {
        return new DestinationPolicy(properties.allowedCidrs(), properties.allowedPorts());
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
    QuotaLimiter quotaLimiter() {
        return new InMemoryQuotaLimiter();
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
            QuotaLimiter quotaLimiter,
            BoundedParser parser,
            McpAuthentication authentication,
            AuditSealer auditSealer,
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
                quotaLimiter,
                parser,
                authentication,
                auditSealer);
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

    /**
     * Comma-separated base64 AES-256 keys from {@code ptmcp.gateway.wal-key}: the first writes new
     * frames, the rest stay readable so segments written before a rotation still replay. Key ids
     * use the same derivation as {@link local.ptmcp.common.crypto.SealedEnvelope}.
     */
    static final class WalKeyRing implements WalKeyProvider {
        private final WalKey current;
        private final Map<String, WalKey> ring = new LinkedHashMap<>();

        WalKeyRing(String configuredKeys) {
            WalKey write = null;
            for (String entry : configuredKeys.split(",")) {
                String value = entry.trim();
                if (value.isEmpty()) {
                    continue;
                }
                byte[] raw;
                try {
                    raw = Base64.getDecoder().decode(value);
                } catch (IllegalArgumentException error) {
                    throw new IllegalStateException(
                            "ptmcp.gateway.wal-key is not valid base64", error);
                }
                if (raw.length != 32) {
                    throw new IllegalStateException(
                            "ptmcp.gateway.wal-key must decode to 32 bytes");
                }
                WalKey key = new WalKey(SealedEnvelope.keyIdOf(raw), raw);
                ring.putIfAbsent(key.keyId(), key);
                if (write == null) {
                    write = key;
                }
            }
            if (write == null) {
                throw new IllegalStateException("ptmcp.gateway.wal-key must hold at least one key");
            }
            this.current = write;
        }

        @Override
        public WalKey current() {
            return current;
        }

        @Override
        public Optional<WalKey> byId(String keyId) {
            return Optional.ofNullable(ring.get(keyId));
        }
    }
}
