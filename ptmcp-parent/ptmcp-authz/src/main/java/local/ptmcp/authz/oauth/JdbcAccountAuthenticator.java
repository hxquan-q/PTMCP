package local.ptmcp.authz.oauth;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.persistence.security.UserSecurityStore;
import local.ptmcp.persistence.security.HostIdentityStore;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;

/**
 * Read-only login-name binding against the RuoYi identity tables. The AS never persists identity
 * data and treats a missing or unreachable account store as an authentication outage.
 */
public final class JdbcAccountAuthenticator implements AccountAuthenticator {
    private static final int BCRYPT_MAX_BYTES = 72;
    private final JdbcTemplate jdbc;
    private final HostIdentityStore host;
    private final PasswordEncoder passwordEncoder;
    private final String fakeHash;

    public JdbcAccountAuthenticator(JdbcTemplate jdbc, PasswordEncoder passwordEncoder) {
        this(jdbc, jdbc.getDataSource(), passwordEncoder);
    }
    public JdbcAccountAuthenticator(JdbcTemplate jdbc, javax.sql.DataSource identity, PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.host = new HostIdentityStore(identity);
        this.passwordEncoder = passwordEncoder;
        this.fakeHash = passwordEncoder.encode(randomSecret());
    }

    @Override
    public Optional<AuthenticatedAccount> authenticate(String loginName, String rawPassword) {
        String normalized = normalize(loginName);
        boolean passwordWithinLimit =
                rawPassword != null
                        && rawPassword.getBytes(StandardCharsets.UTF_8).length <= BCRYPT_MAX_BYTES;
        if (normalized == null) {
            verifyPassword("", rawPassword);
            return Optional.empty();
        }
        HostIdentityStore.Account row;
        UserSecurityStore.State state;
        try {
            // Capture the credential version BEFORE reading the host hash. Rechecking after
            // validation cannot safely attach a new version to an old-password result.
            List<Long> bindings = jdbc.queryForList("SELECT n.user_id FROM sys_user_login_name n JOIN sys_user_profile p ON p.user_id = n.user_id WHERE n.normalized_name = ? AND p.status = 'ACTIVE'", Long.class, normalized);
            if (bindings.size() != 1) {
                verifyPassword(fakeHash, rawPassword);
                return Optional.empty();
            }
            state = new UserSecurityStore(jdbc.getDataSource()).read(bindings.getFirst());
            if (!state.ready()) {
                verifyPassword(fakeHash, rawPassword);
                return Optional.empty();
            }
            row = host.read(bindings.getFirst());
        } catch (DataAccessException | DomainException failure) {
            throw new AuthenticationServiceException("Account store unavailable", failure);
        }
        if (row == null || !row.boundTo(normalized)) {
            verifyPassword(fakeHash, rawPassword);
            return Optional.empty();
        }
        boolean passwordMatches = verifyPassword(row.hash(), rawPassword);
        boolean enabled = row.active();
        if (!passwordMatches
                || !enabled
                || !passwordWithinLimit
                || !StringUtils.hasText(rawPassword)) {
            return Optional.empty();
        }
        return Optional.of(
                new AuthenticatedAccount(row.userId(), row.loginName(), row.displayName(), state.authVersion()));
    }

    static String normalize(String loginName) {
        return HostIdentityStore.normalizeLoginName(loginName);
    }

    private boolean verifyPassword(String hash, String rawPassword) {
        String candidate = rawPassword == null ? "" : rawPassword;
        try {
            return StringUtils.hasText(hash) && passwordEncoder.matches(candidate, hash);
        } catch (RuntimeException malformedHash) {
            return false;
        }
    }

    private static String randomSecret() {
        byte[] value = new byte[32];
        new SecureRandom().nextBytes(value);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

}
