package local.ptmcp.authz.oauth;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Explicit datasource binding; authz never relies on Boot's default DataSource auto-config. */
@ConfigurationProperties("spring.datasource")
public record AuthzDataSourceProperties(
        String url, String username, String password, String driverClassName) {}
