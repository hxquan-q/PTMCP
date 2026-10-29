package local.ptmcp.authz.oauth;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Optional separate read-only host identity connection; otherwise uses the colocated schema. */
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty("ptmcp.authz.identity-datasource.url")
public class AuthzIdentityDataSourceConfiguration {
    @Bean(destroyMethod="close")
    public HikariDataSource authzIdentityDataSource(
            @Value("${ptmcp.authz.identity-datasource.url}") String url,
            @Value("${ptmcp.authz.identity-datasource.username}") String username,
            @Value("${ptmcp.authz.identity-datasource.password}") String credential) {
        HikariConfig config=new HikariConfig();config.setJdbcUrl(url);config.setUsername(username);
        config.setPassword(credential);config.setMaximumPoolSize(2);config.setConnectionTimeout(5000);
        config.setReadOnly(true);config.setPoolName("ptmcp-authz-identity");return new HikariDataSource(config);
    }
}
