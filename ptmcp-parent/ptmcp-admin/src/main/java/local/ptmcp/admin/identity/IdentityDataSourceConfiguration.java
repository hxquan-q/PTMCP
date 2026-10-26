package local.ptmcp.admin.identity;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Optional connection to the host identity store that owns {@code sys_user}. Production supplies
 * {@code ptmcp.admin.identity-datasource.*}; the whole password surface stays uninstalled without
 * it, so no half-wired write path exists. The dev compose database carries the dev identity table
 * in the same schema, so local development points this at the same URL.
 */
@Configuration
@ConditionalOnProperty("ptmcp.admin.identity-datasource.url")
public class IdentityDataSourceConfiguration {
    @Bean(destroyMethod = "close")
    public HikariDataSource identityDataSource(
            @Value("${ptmcp.admin.identity-datasource.url}") String url,
            @Value("${ptmcp.admin.identity-datasource.username}") String username,
            @Value("${ptmcp.admin.identity-datasource.password}") String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(2);
        config.setPoolName("ptmcp-admin-identity");
        config.setInitializationFailTimeout(15_000L);
        return new HikariDataSource(config);
    }

    @Bean
    public IdentityPasswordWriter identityPasswordWriter(
            @org.springframework.beans.factory.annotation.Qualifier("identityDataSource")
                    DataSource identityDataSource) {
        return new IdentityPasswordWriter(identityDataSource);
    }

    @Bean
    public IdentityPasswordService identityPasswordService(
            @org.springframework.beans.factory.annotation.Qualifier("adminDataSource")
                    DataSource dataSource,
            IdentityPasswordWriter writer) {
        return new IdentityPasswordService(dataSource, writer);
    }
}
