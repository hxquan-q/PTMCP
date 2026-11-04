package local.ptmcp.authz.oauth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

/**
 * Serves the content-hashed React assets from the configured bundle directory. Only asset paths are
 * exposed; the page HTML stays behind the controller so it keeps its security headers.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "ptmcp.authz.oauth", name = "enabled", havingValue = "true")
public class OAuthPageAssetsConfiguration implements WebMvcConfigurer {
    private final OAuthPages pages;

    public OAuthPageAssetsConfiguration(OAuthPages pages) {
        this.pages = pages;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        pages.assetsLocation()
                .ifPresent(
                        location ->
                                registry.addResourceHandler(
                                                OAuthSecurityConfiguration.ASSETS_PATTERN)
                                        .addResourceLocations(location)
                                        .setCacheControl(
                                                CacheControl.maxAge(Duration.ofDays(365))
                                                        .cachePublic()
                                                        .immutable()));
    }
}
