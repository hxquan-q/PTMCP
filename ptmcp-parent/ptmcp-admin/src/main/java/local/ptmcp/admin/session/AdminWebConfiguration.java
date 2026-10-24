package local.ptmcp.admin.session;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers the session-derived CSRF gate and the no-store policy for sensitive paths. */
@Configuration
@ConditionalOnProperty("ptmcp.admin.datasource.url")
public class AdminWebConfiguration implements WebMvcConfigurer {
    private final AdminSessionService sessions;

    public AdminWebConfiguration(AdminSessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new NoStoreInterceptor())
                .addPathPatterns("/api/v1/session/**", "/api/v1/me/**");
        registry.addInterceptor(new CsrfInterceptor(sessions))
                .addPathPatterns("/api/v1/admin/**", "/api/v1/me/**");
        registry.addInterceptor(new IdentityParamInterceptor()).addPathPatterns("/api/v1/me/**");
    }
}
