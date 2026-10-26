package local.ptmcp.admin.api;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import local.ptmcp.admin.audit.AuditQueryService;
import local.ptmcp.admin.datasource.CredentialCipher;
import local.ptmcp.admin.datasource.DatasourceAdminService;
import local.ptmcp.admin.datasource.DatasourceConnectionFactory;
import local.ptmcp.admin.datasource.DatasourceJobService;
import local.ptmcp.admin.datasource.DatasourceJobStore;
import local.ptmcp.admin.datasource.DatasourceStore;
import local.ptmcp.admin.datasource.DestinationPolicy;
import local.ptmcp.admin.me.MeService;
import local.ptmcp.admin.me.PersonalTokenService;
import local.ptmcp.admin.metadata.MetadataCatalogService;
import local.ptmcp.admin.platform.ClientAdminService;
import local.ptmcp.admin.platform.PlatformAdminService;
import local.ptmcp.admin.platform.PlatformDirectoryService;
import local.ptmcp.admin.session.AdminSessionService;
import local.ptmcp.admin.session.SessionAuthFilter;
import local.ptmcp.persistence.org.AclDirectory;
import local.ptmcp.persistence.org.AclGrantStore;
import local.ptmcp.persistence.org.DelegationStore;
import local.ptmcp.persistence.org.IdempotencyRecorder;
import local.ptmcp.persistence.org.OrgStore;
import local.ptmcp.persistence.schema.MetadataSyncJobRunner;
import local.ptmcp.persistence.schema.MetadataSyncJobStore;
import local.ptmcp.persistence.schema.SchemaSnapshotStore;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Admin store wiring. The A4 startup slice runs without any datasource, so the whole org/ACL API
 * (stores, controllers and the identity shim) is conditional on {@code ptmcp.admin.datasource.url}
 * and is never half-initialized.
 */
@Configuration
@ConditionalOnProperty("ptmcp.admin.datasource.url")
public class AdminStoresConfiguration {
    @Bean(destroyMethod = "close")
    public HikariDataSource adminDataSource(
            @Value("${ptmcp.admin.datasource.url}") String url,
            @Value("${ptmcp.admin.datasource.username}") String username,
            @Value("${ptmcp.admin.datasource.password}") String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(4);
        config.setPoolName("ptmcp-admin-meta");
        // Database may still be booting behind a container port-forward; fail only after 15s.
        config.setInitializationFailTimeout(15_000L);
        return new HikariDataSource(config);
    }

    @Bean
    public OrgStore orgStore(DataSource dataSource) {
        return new OrgStore(dataSource);
    }

    @Bean
    public AclGrantStore aclGrantStore(DataSource dataSource) {
        return new AclGrantStore(dataSource);
    }

    @Bean
    public IdempotencyRecorder idempotencyRecorder(DataSource dataSource) {
        return new IdempotencyRecorder(dataSource);
    }

    @Bean
    public AclDirectory aclDirectory(DataSource dataSource) {
        return new AclDirectory(dataSource);
    }

    @Bean
    public AdminSessionService adminSessionService(DataSource dataSource) {
        return new AdminSessionService(dataSource);
    }

    @Bean
    public PersonalTokenService personalTokenService(DataSource dataSource) {
        return new PersonalTokenService(dataSource);
    }

    @Bean
    public AuditQueryService auditQueryService(DataSource dataSource) {
        return new AuditQueryService(dataSource);
    }

    @Bean
    public DelegationStore delegationStore(DataSource dataSource) {
        return new DelegationStore(dataSource);
    }

    @Bean
    public PlatformDirectoryService platformDirectoryService(DataSource dataSource) {
        return new PlatformDirectoryService(dataSource);
    }

    @Bean
    public PlatformAdminService platformAdminService(
            DataSource dataSource, CredentialCipher cipher) {
        return new PlatformAdminService(dataSource, cipher);
    }

    @Bean
    public ClientAdminService clientAdminService(DataSource dataSource) {
        return new ClientAdminService(dataSource);
    }

    @Bean
    public MeService meService(
            DataSource dataSource,
            AclDirectory aclDirectory,
            AuditQueryService audits,
            @Value("${ptmcp.mcp.url:}") String mcpUrl) {
        return new MeService(dataSource, aclDirectory, mcpUrl, audits);
    }

    @Bean
    public CredentialCipher credentialCipher(@Value("${ptmcp.admin.credential-key:}") String key) {
        return new CredentialCipher(key);
    }

    @Bean
    public DestinationPolicy destinationPolicy(
            @Value("${ptmcp.admin.datasource.allowed-cidrs:}") String cidrs) {
        return new DestinationPolicy(cidrs);
    }

    @Bean
    public DatasourceStore datasourceStore(DataSource dataSource) {
        return new DatasourceStore(dataSource);
    }

    @Bean
    public DatasourceJobStore datasourceJobStore(DataSource dataSource) {
        return new DatasourceJobStore(dataSource);
    }

    @Bean
    public DatasourceConnectionFactory datasourceConnectionFactory(
            CredentialCipher cipher,
            DestinationPolicy destinationPolicy,
            @Value("${ptmcp.admin.datasource.truststore-path:}") String truststorePath,
            @Value("${ptmcp.admin.datasource.truststore-password:}") String truststorePassword) {
        return new DatasourceConnectionFactory(
                destinationPolicy, cipher, truststorePath, truststorePassword);
    }

    @Bean
    public DatasourceAdminService datasourceAdminService(
            DatasourceStore store, CredentialCipher cipher, DestinationPolicy destinationPolicy) {
        return new DatasourceAdminService(store, cipher, destinationPolicy);
    }

    @Bean
    public MetadataSyncJobStore metadataSyncJobStore(DataSource dataSource) {
        return new MetadataSyncJobStore(dataSource);
    }

    @Bean
    public SchemaSnapshotStore schemaSnapshotStore(DataSource dataSource) {
        return new SchemaSnapshotStore(dataSource);
    }

    @Bean
    public MetadataSyncJobRunner metadataSyncJobRunner(
            DataSource dataSource,
            MetadataSyncJobStore jobStore,
            SchemaSnapshotStore snapshotStore,
            DatasourceConnectionFactory connections,
            DatasourceStore datasources) {
        return new MetadataSyncJobRunner(
                dataSource,
                jobStore,
                snapshotStore,
                datasourceId ->
                        connections.open(
                                datasources.connectionSpec(datasources.get(datasourceId))));
    }

    @Bean
    public DatasourceJobService datasourceJobService(
            DatasourceStore store,
            DatasourceJobStore jobStore,
            MetadataSyncJobRunner runner,
            DatasourceConnectionFactory connections,
            DatasourceAdminService admin) {
        return new DatasourceJobService(store, jobStore, runner, connections, admin);
    }

    @Bean
    public MetadataCatalogService metadataCatalogService(
            DataSource dataSource, SchemaSnapshotStore snapshots) {
        return new MetadataCatalogService(dataSource, snapshots);
    }

    @Bean
    public FilterRegistrationBean<SessionAuthFilter> sessionAuthFilter(
            AdminSessionService sessions) {
        FilterRegistrationBean<SessionAuthFilter> registration =
                new FilterRegistrationBean<>(new SessionAuthFilter(sessions));
        registration.addUrlPatterns("/api/v1/*");
        registration.setOrder(10);
        return registration;
    }

    /**
     * Identity shim for the C5 admin API tests. It must be explicitly enabled; production starts
     * with session authentication only.
     */
    @Bean
    @ConditionalOnProperty("ptmcp.admin.test-principal")
    public FilterRegistrationBean<TestPrincipalFilter> testPrincipalFilter() {
        FilterRegistrationBean<TestPrincipalFilter> registration =
                new FilterRegistrationBean<>(new TestPrincipalFilter());
        registration.addUrlPatterns("/api/v1/*");
        registration.setOrder(20);
        return registration;
    }
}
