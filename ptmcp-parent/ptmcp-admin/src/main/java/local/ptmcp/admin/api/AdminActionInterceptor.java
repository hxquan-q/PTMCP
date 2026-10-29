package local.ptmcp.admin.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.org.DelegationStore;

import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/** Fails closed before binding a body, invoking a controller or replaying an idempotent result. */
public final class AdminActionInterceptor implements HandlerInterceptor {
    private final DelegationStore permissions;

    public AdminActionInterceptor(DelegationStore permissions) {
        this.permissions = permissions;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        AdminPrincipal principal = AdminPrincipal.require(request);
        AdminAction action =
                handler instanceof HandlerMethod method
                        ? method.getMethodAnnotation(AdminAction.class)
                        : null;
        if (action == null
                || !permissions.permissionsOf(principal.userId()).contains(action.value())) {
            throw new DomainException(ErrorCode.MANAGEMENT_SCOPE_DENIED);
        }
        return true;
    }
}
