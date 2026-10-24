package local.ptmcp.admin.session;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.servlet.HandlerInterceptor;

/** Enforces the CSRF header on cookie-authenticated unsafe methods of /admin and /me. */
public final class CsrfInterceptor implements HandlerInterceptor {
    private final AdminSessionService sessions;

    public CsrfInterceptor(AdminSessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!"GET".equals(request.getMethod())
                && !"HEAD".equals(request.getMethod())
                && !"OPTIONS".equals(request.getMethod())
                && Boolean.TRUE.equals(
                        request.getAttribute(
                                AdminSessionService.SESSION_AUTHENTICATED_ATTRIBUTE))) {
            sessions.requireCsrf(request);
        }
        return true;
    }
}
