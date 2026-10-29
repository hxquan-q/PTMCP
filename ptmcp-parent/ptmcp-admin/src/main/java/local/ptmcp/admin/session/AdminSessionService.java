package local.ptmcp.admin.session;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import local.ptmcp.admin.api.AdminPrincipal;
import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.security.HostIdentityStore;
import local.ptmcp.persistence.security.IdentityPasswordChanges;
import local.ptmcp.persistence.security.UserSecurityStore;
import local.ptmcp.persistence.security.WebSessionStore;

import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

/**
 * Opaque ADMIN sessions; every request verifies the durable index and authoritative user version.
 */
public final class AdminSessionService {
    public static final String SESSION_COOKIE = "PTMCP_ADMIN_SESSION";
    public static final String PRESESSION_COOKIE = "PTMCP_ADMIN_PRESESSION";
    public static final String CSRF_HEADER = "X-CSRF-Token";
    public static final String SESSION_ATTRIBUTE = "ptmcp.adminSession";
    public static final String SESSION_AUTHENTICATED_ATTRIBUTE = "ptmcp.sessionAuthenticated";

    private static final Duration SESSION_TTL = Duration.ofHours(8);
    private static final Duration PRESESSION_TTL = Duration.ofMinutes(10);
    private static final Duration LOCK_DURATION = Duration.ofMinutes(10);
    private static final Duration FAILURE_WINDOW = Duration.ofMinutes(10);
    private static final int MAX_FAILURES = 5;
    private static final int PASSWORD_MIN = 12;
    private static final int PASSWORD_MAX = 64;
    private static final int PASSWORD_MAX_BYTES = 72;
    private static final String PLATFORM_SCOPE_PERMISSION = "ptmcp:platform:scope";
    private static final ZoneOffset UTC = ZoneOffset.UTC;

    private final DataSource primary;
    private final HostIdentityStore host;
    private final BCryptPasswordEncoder passwords = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();
    private final String dummyHash =
            passwords.encode(Base64.getEncoder().encodeToString(randomBytes(18)));
    private final Map<String, PendingCsrf> preSessions = new ConcurrentHashMap<>();
    private final Map<String, SessionEntry> sessions = new ConcurrentHashMap<>();
    private final Map<String, Failures> failures = new ConcurrentHashMap<>();

    public AdminSessionService(DataSource primary) {
        this(primary, primary);
    }

    public AdminSessionService(DataSource primary, DataSource identity) {
        this.primary = primary;
        this.host = new HostIdentityStore(identity);
    }

    public record CsrfIssued(String presessionId, String csrfToken, Instant expiresAt) {}

    public record LoginResult(long userId, String displayName, Instant expiresAt) {}

    public record LoginOutcome(String sessionId, LoginResult result) {}

    public record DepartmentView(long id, String name) {}

    public record RoleView(long id, String name) {}

    public record MeView(
            long userId,
            String username,
            String displayName,
            DepartmentView department,
            List<RoleView> roles,
            List<String> permissions,
            List<String> managedScopes,
            boolean platformAdmin,
            Instant expiresAt) {}

    public record SessionContext(long userId, boolean platformAdmin, Instant expiresAt) {}

    private record PendingCsrf(String token, Instant expiresAt) {}

    private record SessionEntry(
            long userId,
            long authVersion,
            boolean platformAdmin,
            Instant issuedAt,
            Instant expiresAt,
            String csrfToken) {}

    private record Failures(int count, Instant firstAt, Instant lockedUntil) {}

    private record Profile(
            long userId,
            String username,
            String displayName,
            long deptId,
            String deptName,
            String passwordHash,
            String status) {}

    /**
     * Anonymous callers receive a short-lived pre-session id (returned so the controller can set
     * the cookie) and a token. Authenticated callers rotate the session CSRF token in place.
     */
    public CsrfIssued issueCsrf(HttpServletRequest request) {
        SessionEntry session = resolveSession(request);
        if (session != null) {
            SessionEntry rotated =
                    new SessionEntry(
                            session.userId(),
                            session.authVersion(),
                            session.platformAdmin(),
                            session.issuedAt(),
                            session.expiresAt(),
                            newToken());
            sessions.computeIfPresent(
                    key(cookie(request, SESSION_COOKIE)), (ignored, previous) -> rotated);
            request.setAttribute(SESSION_ATTRIBUTE, rotated);
            return new CsrfIssued(null, rotated.csrfToken(), rotated.expiresAt());
        }
        Instant now = Instant.now();
        preSessions.values().removeIf(pending -> !pending.expiresAt().isAfter(now));
        String id = newToken();
        String token = newToken();
        Instant expiresAt = now.plus(PRESESSION_TTL);
        preSessions.put(id, new PendingCsrf(token, expiresAt));
        return new CsrfIssued(id, token, expiresAt);
    }

    public ResponseCookie presessionCookie(String preSessionId, boolean secure) {
        return ResponseCookie.from(PRESESSION_COOKIE, preSessionId)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/")
                .maxAge(PRESESSION_TTL)
                .build();
    }

    /** Verifies the pre-session CSRF binding and returns the raw session id for the cookie. */
    public LoginOutcome login(HttpServletRequest request, String username, String password) {
        String preSessionId = requirePresessionCsrf(request);
        if (username == null || password == null || password.isEmpty()) {
            throw new DomainException(ErrorCode.AUTHENTICATION_FAILED);
        }
        String normalized = HostIdentityStore.normalizeLoginName(username);
        if (normalized == null) {
            throw new DomainException(ErrorCode.AUTHENTICATION_FAILED);
        }
        Instant now = Instant.now();
        Failures window = failures.get(normalized);
        if (window != null) {
            if (window.lockedUntil() != null && window.lockedUntil().isAfter(now)) {
                throw new DomainException(ErrorCode.LOGIN_LOCKED);
            }
            if (window.lockedUntil() != null
                    || window.firstAt().plus(FAILURE_WINDOW).isBefore(now)) {
                failures.remove(normalized);
            }
        }
        Long boundUser = findUserId(normalized);
        UserSecurityStore.State captured =
                boundUser == null ? null : new UserSecurityStore(primary).read(boundUser);
        Profile profile = captured != null && captured.ready() ? findProfile(normalized) : null;
        HostIdentityStore.Account account = profile == null ? null : host.read(profile.userId());
        String hash = account == null || !account.boundTo(normalized) ? dummyHash : account.hash();
        boolean matches = passwords.matches(password, hash);
        if (profile == null
                || account == null
                || !account.boundTo(normalized)
                || !account.active()
                || !matches
                || !"ACTIVE".equals(profile.status())) {
            recordFailure(normalized, now);
            throw new DomainException(ErrorCode.AUTHENTICATION_FAILED);
        }
        failures.remove(normalized);
        preSessions.remove(preSessionId);
        SessionEntry created = newEntry(profile, now, captured.authVersion());
        String sessionId = newToken();
        insertSessionIndex(sessionId, profile.userId(), created);
        sessions.put(key(sessionId), created);
        return new LoginOutcome(
                sessionId,
                new LoginResult(profile.userId(), profile.displayName(), created.expiresAt()));
    }

    /** Copies the resolved session onto the request for downstream filters and controllers. */
    public SessionContext attachSession(HttpServletRequest request) {
        SessionEntry session = resolveSession(request);
        if (session == null) {
            return null;
        }
        request.setAttribute(SESSION_ATTRIBUTE, session);
        request.setAttribute(SESSION_AUTHENTICATED_ATTRIBUTE, Boolean.TRUE);
        request.setAttribute(
                AdminPrincipal.ATTRIBUTE,
                new AdminPrincipal(session.userId(), session.platformAdmin()));
        return new SessionContext(session.userId(), session.platformAdmin(), session.expiresAt());
    }

    public MeView me(HttpServletRequest request) {
        SessionEntry session = requireSession(request);
        Profile profile = findProfileById(session.userId());
        if (profile == null || !"ACTIVE".equals(profile.status())) {
            revokeSessionMemory(request);
            throw new DomainException(ErrorCode.SESSION_EXPIRED);
        }
        List<RoleView> roles = roles(session.userId());
        List<String> permissions = permissions(session.userId());
        return new MeView(
                profile.userId(),
                profile.username(),
                profile.displayName(),
                new DepartmentView(profile.deptId(), profile.deptName()),
                roles,
                permissions,
                managedScopes(session.userId()),
                session.platformAdmin(),
                session.expiresAt());
    }

    /** Logging out without a session is a successful no-op and never reveals account state. */
    public void logout(HttpServletRequest request) {
        SessionEntry session = resolveSession(request);
        if (session == null) {
            return;
        }
        requireCsrf(request);
        revokeSessionMemory(request);
    }

    public void changePassword(HttpServletRequest request, String oldPassword, String newPassword) {
        SessionEntry session = requireSession(request);
        requireCsrf(request);
        if (oldPassword == null
                || newPassword == null
                || newPassword.chars().count() < PASSWORD_MIN
                || newPassword.chars().count() > PASSWORD_MAX
                || newPassword.getBytes(StandardCharsets.UTF_8).length > PASSWORD_MAX_BYTES
                || newPassword.equals(oldPassword)) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        UserSecurityStore authority = new UserSecurityStore(primary);
        UserSecurityStore.State captured = authority.read(session.userId());
        UserSecurityStore.requireReady(captured, session.authVersion());
        String boundName = authority.loginName(session.userId());
        HostIdentityStore.Account account = host.read(session.userId());
        if (account == null
                || !account.active()
                || !account.boundTo(boundName)
                || !passwords.matches(oldPassword, account.hash())) {
            throw new DomainException(ErrorCode.AUTHENTICATION_FAILED);
        }
        long profileVersion;
        try (Connection connection = primary.getConnection();
                PreparedStatement query =
                        connection.prepareStatement(
                                "SELECT version FROM sys_user_profile WHERE user_id=?")) {
            query.setQueryTimeout(5);
            query.setLong(1, session.userId());
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) throw new DomainException(ErrorCode.AUTHENTICATION_FAILED);
                profileVersion = result.getLong(1);
            }
        } catch (SQLException failure) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        new IdentityPasswordChanges(primary, host)
                .begin(
                        session.userId(),
                        profileVersion,
                        session.authVersion(),
                        HostIdentityStore.fingerprint(account.hash()),
                        passwords.encode(newPassword),
                        session.userId());
        sessions.entrySet().removeIf(entry -> entry.getValue().userId() == session.userId());
    }

    /**
     * Session-scoped CSRF check. When Spring Security is absent this is the only barrier protecting
     * cookie-authenticated writes, so it rejects a missing or stale token on every unsafe method.
     */
    public void requireCsrf(HttpServletRequest request) {
        SessionEntry session = requireSession(request);
        String presented = request.getHeader(CSRF_HEADER);
        if (presented == null || !constantTimeEquals(presented, session.csrfToken())) {
            throw new DomainException(ErrorCode.CSRF_INVALID);
        }
    }

    public SessionContext resolve(HttpServletRequest request) {
        SessionEntry session = resolveSession(request);
        return session == null
                ? null
                : new SessionContext(
                        session.userId(), session.platformAdmin(), session.expiresAt());
    }

    public SessionEntry requireSession(HttpServletRequest request) {
        SessionEntry session = resolveSession(request);
        if (session == null) {
            throw new DomainException(
                    cookie(request, SESSION_COOKIE) == null
                            ? ErrorCode.AUTHENTICATION_REQUIRED
                            : ErrorCode.SESSION_EXPIRED);
        }
        return session;
    }

    /**
     * Identity for the personal portal: an ADMIN cookie session only. The test-principal shim is
     * deliberately not accepted here.
     */
    public long requireSessionUserId(HttpServletRequest request) {
        return requireSession(request).userId();
    }

    public long requireSessionAuthVersion(HttpServletRequest request) {
        return requireSession(request).authVersion();
    }

    public ResponseCookie sessionCookie(String sessionId, boolean secure) {
        return ResponseCookie.from(SESSION_COOKIE, sessionId)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/")
                .maxAge(SESSION_TTL)
                .build();
    }

    public static ResponseCookie clearCookie(String name, boolean secure) {
        return ResponseCookie.from(name, "")
                .httpOnly(true)
                .secure(secure)
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ZERO)
                .build();
    }

    public boolean platformAdmin(long userId) {
        return permissions(userId).contains(PLATFORM_SCOPE_PERMISSION);
    }

    private SessionEntry resolveSession(HttpServletRequest request) {
        Object cached = request.getAttribute(SESSION_ATTRIBUTE);
        if (cached instanceof SessionEntry entry) {
            return entry;
        }
        String sessionId = cookie(request, SESSION_COOKIE);
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        String key = key(sessionId);
        SessionEntry session = sessions.get(key);
        if (session == null || !session.expiresAt().isAfter(Instant.now())) {
            sessions.remove(key);
            return null;
        }
        boolean indexed =
                new WebSessionStore(primary)
                        .active("ADMIN", sessionId, session.userId(), session.authVersion());
        if (!indexed) {
            sessions.remove(key);
            return null;
        }
        return session;
    }

    private void revokeSessionMemory(HttpServletRequest request) {
        String sessionId = cookie(request, SESSION_COOKIE);
        if (sessionId == null) {
            return;
        }
        String key = key(sessionId);
        SessionEntry removed = sessions.remove(key);
        if (removed != null) {
            revokeIndex(key);
        }
        request.removeAttribute(SESSION_ATTRIBUTE);
        request.removeAttribute(SESSION_AUTHENTICATED_ATTRIBUTE);
    }

    private SessionEntry newEntry(Profile profile, Instant now, long authVersion) {
        return new SessionEntry(
                profile.userId(),
                authVersion,
                platformAdmin(profile.userId()),
                now,
                now.plus(SESSION_TTL),
                newToken());
    }

    private void recordFailure(String username, Instant now) {
        Failures updated =
                failures.compute(
                        username,
                        (key, previous) -> {
                            boolean expired =
                                    previous == null
                                            || previous.firstAt().plus(FAILURE_WINDOW).isBefore(now)
                                            || (previous.lockedUntil() != null
                                                    && !previous.lockedUntil().isAfter(now));
                            if (expired) {
                                return new Failures(1, now, null);
                            }
                            int count = previous.count() + 1;
                            Instant locked = count >= MAX_FAILURES ? now.plus(LOCK_DURATION) : null;
                            return new Failures(count, previous.firstAt(), locked);
                        });
        if (updated.lockedUntil() != null) {
            throw new DomainException(ErrorCode.LOGIN_LOCKED);
        }
    }

    private String requirePresessionCsrf(HttpServletRequest request) {
        String id = cookie(request, PRESESSION_COOKIE);
        String presented = request.getHeader(CSRF_HEADER);
        PendingCsrf pending = id == null ? null : preSessions.get(id);
        if (pending == null
                || !pending.expiresAt().isAfter(Instant.now())
                || presented == null
                || !constantTimeEquals(presented, pending.token())) {
            throw new DomainException(ErrorCode.CSRF_INVALID);
        }
        return id;
    }

    private void insertSessionIndex(String sessionId, long userId, SessionEntry session) {
        new WebSessionStore(primary)
                .issue(
                        "ADMIN",
                        sessionId,
                        userId,
                        session.authVersion(),
                        session.issuedAt(),
                        session.expiresAt());
    }

    private void revokeIndex(String sessionHash) {
        try (Connection connection = primary.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "UPDATE web_session_index SET revoked_at = ?,"
                                        + " version = version + 1, update_time = ?"
                                        + " WHERE session_hash = ? AND revoked_at IS NULL")) {
            statement.setObject(1, utc(Instant.now()));
            statement.setObject(2, utc(Instant.now()));
            statement.setBytes(3, hexToBytes(sessionHash));
            statement.executeUpdate();
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
        }
    }

    private Long findUserId(String normalizedName) {
        try (Connection connection = primary.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT user_id FROM sys_user_login_name WHERE normalized_name = ?")) {
            statement.setQueryTimeout(5);
            statement.setString(1, normalizedName);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong(1) : null;
            }
        } catch (SQLException failure) {
            throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
        }
    }

    private Profile findProfile(String normalizedName) {
        try (Connection connection = primary.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT p.user_id, l.normalized_name, p.display_name, p.dept_id,"
                                        + " d.dept_name, p.password_hash, p.status"
                                        + " FROM sys_user_login_name l"
                                        + " JOIN sys_user_profile p ON p.user_id = l.user_id"
                                        + " LEFT JOIN sys_dept d ON d.dept_id = p.dept_id"
                                        + " WHERE l.normalized_name = ?")) {
            statement.setString(1, normalizedName);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? profile(result) : null;
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private Profile findProfileById(long userId) {
        try (Connection connection = primary.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT p.user_id, l.normalized_name, p.display_name, p.dept_id,"
                                        + " d.dept_name, p.password_hash, p.status"
                                        + " FROM sys_user_profile p"
                                        + " JOIN sys_user_login_name l ON l.user_id = p.user_id"
                                        + " LEFT JOIN sys_dept d ON d.dept_id = p.dept_id"
                                        + " WHERE p.user_id = ?")) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? profile(result) : null;
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private List<RoleView> roles(long userId) {
        List<RoleView> roles = new ArrayList<>();
        try (Connection connection = primary.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT r.role_id, r.role_name FROM sys_user_role ur"
                                        + " JOIN sys_role r ON r.role_id = ur.role_id"
                                        + " WHERE ur.user_id = ? AND r.status = '0'"
                                        + " AND r.del_flag = '0' ORDER BY r.role_id")) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    roles.add(new RoleView(result.getLong(1), result.getString(2)));
                }
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        return roles;
    }

    private List<String> permissions(long userId) {
        List<String> permissions = new ArrayList<>();
        try (Connection connection = primary.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT DISTINCT rp.permission_code FROM sys_user_role ur"
                                        + " JOIN sys_role r ON r.role_id = ur.role_id"
                                        + " JOIN sys_role_permission rp ON rp.role_id = r.role_id"
                                        + " WHERE ur.user_id = ? AND r.status = '0'"
                                        + " AND r.del_flag = '0' ORDER BY rp.permission_code")) {
            statement.setLong(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    permissions.add(result.getString(1));
                }
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        return permissions;
    }

    private List<String> managedScopes(long userId) {
        Map<String, String> scopes = new LinkedHashMap<>();
        try (Connection connection = primary.getConnection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT g.resource_type, g.resource_id FROM acl_grant g"
                                        + " WHERE g.status = 'ACTIVE' AND g.effect = 'ALLOW'"
                                        + " AND (g.valid_to IS NULL OR g.valid_to > ?)"
                                        + " AND (g.subject_type = 'USER' AND g.subject_id = ?"
                                        + "   OR g.subject_type = 'ROLE' AND g.subject_id IN"
                                        + "     (SELECT role_id FROM sys_user_role WHERE user_id = ?)"
                                        + "   OR g.subject_type = 'DEPT' AND g.subject_id IN"
                                        + "     (SELECT d.dept_id FROM sys_user_profile p"
                                        + "        JOIN sys_dept d ON d.dept_id = p.dept_id"
                                        + "       WHERE p.user_id = ?))"
                                        + " ORDER BY g.resource_type, g.resource_id")) {
            statement.setObject(1, utc(Instant.now()));
            statement.setLong(2, userId);
            statement.setLong(3, userId);
            statement.setLong(4, userId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String scope = result.getString(1) + ":" + result.getLong(2);
                    scopes.put(scope, scope);
                }
            }
        } catch (SQLException error) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        return List.copyOf(scopes.values());
    }

    private static Profile profile(ResultSet result) throws SQLException {
        return new Profile(
                result.getLong(1),
                result.getString(2),
                result.getString(3),
                result.getLong(4),
                result.getString(5) == null ? "" : result.getString(5),
                result.getString(6),
                result.getString(7));
    }

    private static String cookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private static String key(String sessionId) {
        return sessionId == null ? "" : hashHex(sessionId.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private String newToken() {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(32));
    }

    private byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        random.nextBytes(bytes);
        return bytes;
    }

    private static String hashHex(byte[] input) {
        return java.util.HexFormat.of().formatHex(hashBytes(input));
    }

    private static byte[] hashBytes(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static byte[] hexToBytes(String hex) {
        return java.util.HexFormat.of().parseHex(hex);
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, UTC);
    }
}
