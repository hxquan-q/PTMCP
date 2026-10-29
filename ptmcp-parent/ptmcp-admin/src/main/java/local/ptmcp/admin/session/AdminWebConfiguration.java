package local.ptmcp.admin.session;

import local.ptmcp.admin.api.AdminActionInterceptor;
import local.ptmcp.persistence.org.DelegationStore;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers the session-derived CSRF gate and the no-store policy for sensitive paths. */
@Configuration
@ConditionalOnProperty("ptmcp.admin.datasource.url")
public class AdminWebConfiguration implements WebMvcConfigurer {
    private final AdminSessionService sessions;
    private final DelegationStore permissions;

    public AdminWebConfiguration(AdminSessionService sessions, DelegationStore permissions) {
        this.sessions = sessions;
        this.permissions = permissions;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new NoStoreInterceptor())
                .addPathPatterns("/api/v1/session/**", "/api/v1/me/**", "/api/v1/admin/**");
        registry.addInterceptor(new CsrfInterceptor(sessions))
                .addPathPatterns("/api/v1/admin/**", "/api/v1/me/**");
        registry.addInterceptor(new AdminActionInterceptor(permissions))
                .addPathPatterns("/api/v1/admin/**");
        registry.addInterceptor(new IdentityParamInterceptor()).addPathPatterns("/api/v1/me/**");
    }
}
