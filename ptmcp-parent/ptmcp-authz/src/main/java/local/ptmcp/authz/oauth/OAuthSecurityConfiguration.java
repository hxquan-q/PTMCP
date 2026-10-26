package local.ptmcp.authz.oauth;

import jakarta.servlet.DispatcherType;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationServerMetadata;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationServerMetadataClaimNames;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenGenerator;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.DispatcherTypeRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.List;

/**
 * AS filter chains: closed endpoints first, then the four advertised endpoints, then the login and
 * consent pages, and a final deny. Every chain except the page chain is stateless.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "ptmcp.authz.oauth", name = "enabled", havingValue = "true")
public class OAuthSecurityConfiguration {
    static final String LOGIN_PAGE = "/login";
    static final String CONSENT_PAGE = "/oauth2/consent";
    static final String SESSION_COOKIE = "PTMCP_AS_SESSION";

    private static final List<String> CLOSED_ENDPOINTS =
            List.of(
                    "/oauth2/introspect",
                    "/oauth2/par",
                    "/oauth2/device_authorization",
                    "/oauth2/device_verification",
                    "/connect/register",
                    "/userinfo",
                    "/connect/logout");

    @Bean
    @Order(1)
    SecurityFilterChain closedOAuthEndpoints(HttpSecurity http) throws Exception {
        http.securityMatcher(anyPath(CLOSED_ENDPOINTS))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().denyAll())
                .csrf(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain authorizationServer(
            HttpSecurity http,
            OAuthProperties properties,
            RegisteredClientRepository clients,
            OAuth2AuthorizationService authorizations,
            OAuth2AuthorizationConsentService consents,
            OAuth2TokenGenerator<?> tokenGenerator,
            AuthorizationServerSettings settings)
            throws Exception {
        OAuth2AuthorizationServerConfigurer server = new OAuth2AuthorizationServerConfigurer();
        http.securityMatcher(server.getEndpointsMatcher())
                .with(
                        server,
                        configurer ->
                                configurer
                                        .registeredClientRepository(clients)
                                        .authorizationService(authorizations)
                                        .authorizationConsentService(consents)
                                        .authorizationServerSettings(settings)
                                        .tokenGenerator(tokenGenerator)
                                        .authorizationEndpoint(
                                                endpoint ->
                                                        endpoint.consentPage(CONSENT_PAGE)
                                                                .authorizationRequestConverter(
                                                                        new AuthorizationRequestPolicy(
                                                                                clients,
                                                                                properties
                                                                                        .audience())))
                                        .clientAuthentication(
                                                clientAuthentication ->
                                                        clientAuthentication
                                                                .authenticationConverter(
                                                                        new PublicRefreshClientAuthenticationConverter())
                                                                .authenticationConverter(
                                                                        new PublicRevocationClientAuthenticationConverter())
                                                                .authenticationProvider(
                                                                        new PublicRefreshClientAuthenticationProvider(
                                                                                clients))
                                                                .authenticationProvider(
                                                                        new PublicRevocationClientAuthenticationProvider(
                                                                                clients)))
                                        .tokenRevocationEndpoint(
                                                revocation ->
                                                        revocation.authenticationProvider(
                                                                new PtmcpTokenRevocationAuthenticationProvider(
                                                                        authorizations)))
                                        .authorizationServerMetadataEndpoint(
                                                metadata ->
                                                        metadata
                                                                .authorizationServerMetadataCustomizer(
                                                                        OAuthSecurityConfiguration
                                                                                ::advertiseOnlySupported)))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .exceptionHandling(
                        exceptions ->
                                exceptions.defaultAuthenticationEntryPointFor(
                                        new LoginUrlAuthenticationEntryPoint(LOGIN_PAGE),
                                        PathPatternRequestMatcher.withDefaults()
                                                .matcher("/oauth2/authorize")));
        return http.build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain pages(
            HttpSecurity http, AccountAuthenticationProvider accountAuthenticationProvider)
            throws Exception {
        http.securityMatcher(pageMatcher())
                .authorizeHttpRequests(
                        authorize ->
                                authorize
                                        .dispatcherTypeMatchers(DispatcherType.ERROR)
                                        .permitAll()
                                        .requestMatchers(LOGIN_PAGE, "/actuator/health")
                                        .permitAll()
                                        .anyRequest()
                                        .authenticated())
                .formLogin(form -> form.loginPage(LOGIN_PAGE).permitAll())
                .authenticationProvider(accountAuthenticationProvider)
                .logout(
                        logout ->
                                logout.logoutUrl("/logout")
                                        .logoutSuccessUrl(LOGIN_PAGE + "?logout")
                                        .deleteCookies(SESSION_COOKIE)
                                        .invalidateHttpSession(true)
                                        .clearAuthentication(true))
                .sessionManagement(
                        session -> session.sessionFixation(fixation -> fixation.changeSessionId()));
        return http.build();
    }

    @Bean
    @Order(4)
    SecurityFilterChain denyEverythingElse(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(authorize -> authorize.anyRequest().denyAll())
                .csrf(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    /**
     * The stock metadata builder advertises grants, client auth methods and endpoints this server
     * does not implement. Trim before the document is written so discovery cannot route a client
     * into a 403.
     */
    static void advertiseOnlySupported(OAuth2AuthorizationServerMetadata.Builder metadata) {
        metadata.grantTypes(
                grants ->
                        grants.retainAll(
                                List.of(
                                        AuthorizationGrantType.AUTHORIZATION_CODE.getValue(),
                                        AuthorizationGrantType.REFRESH_TOKEN.getValue())));
        metadata.tokenEndpointAuthenticationMethods(
                methods -> {
                    methods.retainAll(
                            List.of(
                                    ClientAuthenticationMethod.CLIENT_SECRET_BASIC.getValue(),
                                    ClientAuthenticationMethod.CLIENT_SECRET_POST.getValue()));
                    methods.add(ClientAuthenticationMethod.NONE.getValue());
                });
        metadata.tokenRevocationEndpointAuthenticationMethods(
                methods -> methods.add(ClientAuthenticationMethod.NONE.getValue()));
        metadata.claims(
                claims -> {
                    claims.remove(
                            OAuth2AuthorizationServerMetadataClaimNames.INTROSPECTION_ENDPOINT);
                    claims.remove(
                            OAuth2AuthorizationServerMetadataClaimNames
                                    .INTROSPECTION_ENDPOINT_AUTH_METHODS_SUPPORTED);
                    claims.remove(
                            OAuth2AuthorizationServerMetadataClaimNames.REGISTRATION_ENDPOINT);
                    claims.remove(
                            OAuth2AuthorizationServerMetadataClaimNames
                                    .PUSHED_AUTHORIZATION_REQUEST_ENDPOINT);
                    claims.remove(
                            OAuth2AuthorizationServerMetadataClaimNames
                                    .DEVICE_AUTHORIZATION_ENDPOINT);
                    claims.remove(
                            OAuth2AuthorizationServerMetadataClaimNames
                                    .TLS_CLIENT_CERTIFICATE_BOUND_ACCESS_TOKENS);
                    claims.remove(
                            OAuth2AuthorizationServerMetadataClaimNames
                                    .DPOP_SIGNING_ALG_VALUES_SUPPORTED);
                });
    }

    private static RequestMatcher pageMatcher() {
        return new OrRequestMatcher(
                anyPath(List.of(LOGIN_PAGE, CONSENT_PAGE, "/logout", "/actuator/health")),
                new DispatcherTypeRequestMatcher(DispatcherType.ERROR));
    }

    private static RequestMatcher anyPath(List<String> paths) {
        RequestMatcher[] matchers =
                paths.stream()
                        .map(path -> PathPatternRequestMatcher.withDefaults().matcher(path))
                        .toArray(RequestMatcher[]::new);
        return new OrRequestMatcher(matchers);
    }
}
