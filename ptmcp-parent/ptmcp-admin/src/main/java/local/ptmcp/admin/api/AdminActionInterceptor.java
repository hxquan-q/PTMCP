package local.ptmcp.admin.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Binary management gate: the single platform administrator owns every admin action and needs no
 * capability codes; everyone else is an ordinary user and is denied the admin surface entirely.
 */
public final class AdminActionInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!AdminPrincipal.require(request).platformAdmin()) {
            throw new DomainException(ErrorCode.MANAGEMENT_SCOPE_DENIED);
        }
        return true;
    }
}
