package local.ptmcp.authz.oauth;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.zaxxer.hikari.HikariDataSource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.DelegatingOAuth2TokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.JwtGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2AccessTokenGenerator;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/** AS persistence and token wiring; every bean exists only when OAuth is explicitly enabled. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "ptmcp.authz.oauth", name = "enabled", havingValue = "true")
@EnableConfigurationProperties({OAuthProperties.class, AuthzDataSourceProperties.class})
public class OAuthDataConfiguration {

    @Bean
    @org.springframework.context.annotation.Primary
    public DataSource oauthDataSource(AuthzDataSourceProperties datasource) {
        if (datasource.url() == null || datasource.url().isBlank()) {
            throw new IllegalStateException(
                    "spring.datasource.url is required when OAuth is enabled");
        }
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setPoolName("ptmcp-authz");
        dataSource.setJdbcUrl(datasource.url());
        dataSource.setUsername(datasource.username());
        dataSource.setPassword(datasource.password());
        if (datasource.driverClassName() != null && !datasource.driverClassName().isBlank()) {
            dataSource.setDriverClassName(datasource.driverClassName());
        }
        dataSource.setMaximumPoolSize(4);
        dataSource.setMinimumIdle(1);
        dataSource.setConnectionTimeout(5_000);
        return dataSource;
    }

    @Bean
    AsSessionSecurity asSessionSecurity(DataSource dataSource) {
        return new AsSessionSecurity(
                new local.ptmcp.persistence.security.WebSessionStore(dataSource));
    }

    @Bean
    public JdbcTemplate oauthJdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    public PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    public PtmcpRegisteredClientRepository registeredClientRepository(
            JdbcTemplate oauthJdbcTemplate, OAuthProperties properties) {
        return new PtmcpRegisteredClientRepository(oauthJdbcTemplate, properties);
    }

    @Bean
    public PtmcpOAuth2AuthorizationService authorizationService(
            JdbcTemplate oauthJdbcTemplate,
            PtmcpRegisteredClientRepository clients,
            OAuthProperties properties) {
        return new PtmcpOAuth2AuthorizationService(oauthJdbcTemplate, clients, properties);
    }

    @Bean
    public OAuth2AuthorizationConsentService authorizationConsentService(
            JdbcTemplate oauthJdbcTemplate) {
        return new PtmcpOAuth2AuthorizationConsentService(oauthJdbcTemplate);
    }

    @Bean
    public AccountAuthenticator accountAuthenticator(
            JdbcTemplate oauthJdbcTemplate,
            @org.springframework.beans.factory.annotation.Qualifier("authzIdentityDataSource")
                    org.springframework.beans.factory.ObjectProvider<DataSource> identity) {
        return new JdbcAccountAuthenticator(
                oauthJdbcTemplate,
                identity.getIfAvailable(oauthJdbcTemplate::getDataSource),
                new BCryptPasswordEncoder());
    }

    @Bean
    public AccountAuthenticationProvider accountAuthenticationProvider(
            AccountAuthenticator accountAuthenticator) {
        return new AccountAuthenticationProvider(accountAuthenticator);
    }

    @Bean
    public JWKSource<SecurityContext> jwkSource(OAuthProperties properties) {
        return RsaJwkSourceFactory.fromPemFile(properties.signingKeyFile());
    }

    @Bean
    public JwtEncoder jwtEncoder(JWKSource<SecurityContext> jwkSource) {
        return new NimbusJwtEncoder(jwkSource);
    }

    @Bean
    public JwtDecoder revocationJwtDecoder(
            JWKSource<SecurityContext> jwkSource, OAuthProperties properties) {
        NimbusJwtDecoder decoder =
                (NimbusJwtDecoder) OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }

    @Bean
    public OAuth2TokenCustomizer<JwtEncodingContext> jwtCustomizer(OAuthProperties properties) {
        return new PtmcpJwtCustomizer(properties);
    }

    @Bean
    public OAuth2TokenGenerator<?> tokenGenerator(
            JwtEncoder jwtEncoder,
            OAuth2TokenCustomizer<JwtEncodingContext> jwtCustomizer,
            OAuthProperties properties) {
        JwtGenerator jwtGenerator = new JwtGenerator(jwtEncoder);
        jwtGenerator.setJwtCustomizer(jwtCustomizer);
        return new DelegatingOAuth2TokenGenerator(
                jwtGenerator,
                new OAuth2AccessTokenGenerator(),
                new PtmcpRefreshTokenGenerator(properties));
    }

    @Bean
    public AuthorizationServerSettings authorizationServerSettings(OAuthProperties properties) {
        return AuthorizationServerSettings.builder().issuer(properties.issuer()).build();
    }
}
