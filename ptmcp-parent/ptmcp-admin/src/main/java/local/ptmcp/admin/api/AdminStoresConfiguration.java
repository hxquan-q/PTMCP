package local.ptmcp.admin.api;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import local.ptmcp.persistence.org.AclDirectory;
import local.ptmcp.persistence.org.AclGrantStore;
import local.ptmcp.persistence.org.IdempotencyRecorder;
import local.ptmcp.persistence.org.OrgStore;

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
    public FilterRegistrationBean<TestPrincipalFilter> testPrincipalFilter() {
        return new FilterRegistrationBean<>(new TestPrincipalFilter());
    }
}
