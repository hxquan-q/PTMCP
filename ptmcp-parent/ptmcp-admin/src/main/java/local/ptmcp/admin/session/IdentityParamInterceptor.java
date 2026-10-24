package local.ptmcp.admin.session;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import org.springframework.web.servlet.HandlerInterceptor;

/**
 * The personal portal derives identity only from the session. A {@code userId} query parameter is
 * rejected outright instead of being ignored, so a caller can never mistake it for an override.
 */
public final class IdentityParamInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getParameterMap().containsKey("userId")) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        return true;
    }
}
