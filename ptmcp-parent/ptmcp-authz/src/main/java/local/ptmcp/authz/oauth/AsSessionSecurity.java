package local.ptmcp.authz.oauth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.persistence.security.WebSessionStore;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

/** Checks durable user/session state before authorize, consent and already-approved shortcuts. */
final class AsSessionSecurity {
    private static final Duration ABSOLUTE_TTL = Duration.ofHours(8);
    private final WebSessionStore sessions;
    private final SavedRequestAwareAuthenticationSuccessHandler success =
            new SavedRequestAwareAuthenticationSuccessHandler();

    AsSessionSecurity(WebSessionStore sessions) {
        this.sessions = sessions;
    }

    void onSuccess(
            HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException, ServletException {
        if (!(authentication.getPrincipal() instanceof AsPrincipal principal)) {
            reject(request);
            response.sendRedirect(OAuthSecurityConfiguration.LOGIN_PAGE + "?error");
            return;
        }
        HttpSession session = request.getSession();
        Instant now = Instant.now();
        try {
            sessions.issue(
                    "AS",
                    session.getId(),
                    principal.userId(),
                    principal.authVersion(),
                    now,
                    now.plus(ABSOLUTE_TTL));
            session.setMaxInactiveInterval((int) ABSOLUTE_TTL.toSeconds());
        } catch (DomainException unavailable) {
            reject(request);
            response.sendRedirect(OAuthSecurityConfiguration.LOGIN_PAGE + "?error");
            return;
        }
        success.onAuthenticationSuccess(request, response, authentication);
    }

    void onLogout(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            sessions.revoke("AS", session.getId());
        }
    }

    OncePerRequestFilter filter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(
                    HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                Authentication authentication =
                        SecurityContextHolder.getContext().getAuthentication();
                if (authentication != null
                        && authentication.getPrincipal() instanceof AsPrincipal principal) {
                    HttpSession session = request.getSession(false);
                    try {
                        if (session == null
                                || !sessions.active(
                                        "AS",
                                        session.getId(),
                                        principal.userId(),
                                        principal.authVersion())) {
                            reject(request);
                        }
                    } catch (DomainException unavailable) {
                        reject(request);
                        response.sendError(503);
                        return;
                    }
                }
                chain.doFilter(request, response);
            }
        };
    }

    private static void reject(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }
}
