package local.ptmcp.admin.session;

import jakarta.servlet.http.HttpServletRequest;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** API-spec §3 session endpoints. Identity is carried only by the opaque cookie. */
@RestController
@RequestMapping("/api/v1/session")
@ConditionalOnProperty("ptmcp.admin.datasource.url")
public class SessionController {
    private final AdminSessionService sessions;
    private final boolean secureCookies;

    public SessionController(
            AdminSessionService sessions,
            @Value("${ptmcp.admin.cookie-secure:true}") boolean secureCookies) {
        this.sessions = sessions;
        this.secureCookies = secureCookies;
    }

    public record CsrfResponse(String csrfToken, Instant expiresAt) {}

    public record LoginRequest(String username, String password) {}

    public record LoginResponse(long userId, String displayName, Instant expiresAt) {}

    public record LogoutResponse(boolean loggedOut) {}

    public record PasswordRequest(String oldPassword, String newPassword) {}

    public record PasswordResponse(boolean reauthenticationRequired) {}

    @GetMapping("/csrf")
    public ResponseEntity<CsrfResponse> csrf(HttpServletRequest request) {
        requireSameOrigin(request);
        AdminSessionService.CsrfIssued issued = sessions.issueCsrf(request);
        ResponseEntity.BodyBuilder response =
                ResponseEntity.ok()
                        .header("Cache-Control", "no-store")
                        .header("X-Content-Type-Options", "nosniff");
        if (issued.presessionId() != null) {
            response.header(
                    HttpHeaders.SET_COOKIE,
                    sessions.presessionCookie(issued.presessionId(), secureCookies).toString());
        }
        return response.body(new CsrfResponse(issued.csrfToken(), issued.expiresAt()));
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @RequestBody LoginRequest body, HttpServletRequest request) {
        requireSameOrigin(request);
        if (body == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        AdminSessionService.LoginOutcome outcome =
                sessions.login(request, body.username(), body.password());
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .header(
                        HttpHeaders.SET_COOKIE,
                        sessions.sessionCookie(outcome.sessionId(), secureCookies).toString())
                .header(
                        HttpHeaders.SET_COOKIE,
                        AdminSessionService.clearCookie(
                                        AdminSessionService.PRESESSION_COOKIE, secureCookies)
                                .toString())
                .body(
                        new LoginResponse(
                                outcome.result().userId(),
                                outcome.result().displayName(),
                                outcome.result().expiresAt()));
    }

    @GetMapping("/me")
    public AdminSessionService.MeView me(HttpServletRequest request) {
        return sessions.me(request);
    }

    @PostMapping("/logout")
    public ResponseEntity<LogoutResponse> logout(HttpServletRequest request) {
        requireSameOrigin(request);
        sessions.logout(request);
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.SET_COOKIE,
                        AdminSessionService.clearCookie(
                                        AdminSessionService.SESSION_COOKIE, secureCookies)
                                .toString())
                .header(
                        HttpHeaders.SET_COOKIE,
                        AdminSessionService.clearCookie(
                                        AdminSessionService.PRESESSION_COOKIE, secureCookies)
                                .toString())
                .body(new LogoutResponse(true));
    }

    @PutMapping("/password")
    public ResponseEntity<PasswordResponse> password(
            @RequestBody PasswordRequest body, HttpServletRequest request) {
        requireSameOrigin(request);
        if (body == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        sessions.changePassword(request, body.oldPassword(), body.newPassword());
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .header(
                        HttpHeaders.SET_COOKIE,
                        AdminSessionService.clearCookie(
                                        AdminSessionService.SESSION_COOKIE, secureCookies)
                                .toString())
                .body(new PasswordResponse(true));
    }

    /**
     * Browser cross-site requests must not be able to mint pre-sessions or log a victim in. Only
     * the presence of an explicit cross-site signal is rejected; non-browser clients send no
     * header.
     */
    static void requireSameOrigin(HttpServletRequest request) {
        String site = request.getHeader("Sec-Fetch-Site");
        if (site != null && !"same-origin".equals(site) && !"none".equals(site))
            throw new DomainException(ErrorCode.CSRF_INVALID);
        String origin = request.getHeader(HttpHeaders.ORIGIN);
        String referer = request.getHeader(HttpHeaders.REFERER);
        if (origin != null) requireOrigin(request, origin, true);
        else if (referer != null) requireOrigin(request, referer, false);
    }

    private static void requireOrigin(
            HttpServletRequest request, String value, boolean originHeader) {
        try {
            java.net.URI uri = java.net.URI.create(value);
            String host = request.getServerName();
            int port =
                    uri.getPort() < 0
                            ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80)
                            : uri.getPort();
            if (uri.getHost() == null
                    || uri.getRawUserInfo() != null
                    || uri.getRawFragment() != null
                    || !request.getScheme().equalsIgnoreCase(uri.getScheme())
                    || !host.equalsIgnoreCase(uri.getHost())
                    || request.getServerPort() != port
                    || (originHeader && (uri.getRawQuery() != null || !uri.getRawPath().isEmpty())))
                throw new IllegalArgumentException();
        } catch (IllegalArgumentException failure) {
            throw new DomainException(ErrorCode.CSRF_INVALID);
        }
    }
}
