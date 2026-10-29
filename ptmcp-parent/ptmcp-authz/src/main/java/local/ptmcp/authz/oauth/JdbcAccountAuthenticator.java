package local.ptmcp.authz.oauth;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.persistence.security.UserSecurityStore;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Read-only login-name binding against the RuoYi identity tables. The AS never persists identity
 * data and treats a missing or unreachable account store as an authentication outage.
 */
public final class JdbcAccountAuthenticator implements AccountAuthenticator {
    private static final int BCRYPT_MAX_BYTES = 72;
    private static final String SELECT_USER =
            "SELECT u.user_id, u.user_name, u.nick_name, u.password, u.status, u.del_flag "
                    + "FROM sys_user u JOIN sys_user_login_name n ON n.user_id = u.user_id "
                    + "WHERE n.normalized_name = ?";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final String fakeHash;

    public JdbcAccountAuthenticator(JdbcTemplate jdbc, PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
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
        List<AccountRow> rows;
        UserSecurityStore.State state;
        try {
            // Capture the credential version BEFORE reading the host hash. Rechecking after
            // validation cannot safely attach a new version to an old-password result.
            List<Long> bindings = jdbc.queryForList("SELECT user_id FROM sys_user_login_name WHERE normalized_name = ?", Long.class, normalized);
            if (bindings.size() != 1) {
                verifyPassword(fakeHash, rawPassword);
                return Optional.empty();
            }
            state = new UserSecurityStore(jdbc.getDataSource()).read(bindings.getFirst());
            if (!state.ready()) {
                verifyPassword(fakeHash, rawPassword);
                return Optional.empty();
            }
            rows =
                    jdbc.query(
                            SELECT_USER,
                            (rs, index) ->
                                    new AccountRow(
                                            rs.getLong("user_id"),
                                            rs.getString("user_name"),
                                            rs.getString("nick_name"),
                                            rs.getString("password"),
                                            rs.getString("status"),
                                            rs.getString("del_flag")),
                            normalized);
        } catch (DataAccessException | DomainException failure) {
            throw new AuthenticationServiceException("Account store unavailable", failure);
        }
        if (rows.isEmpty()) {
            verifyPassword(fakeHash, rawPassword);
            return Optional.empty();
        }
        AccountRow row = rows.getFirst();
        boolean passwordMatches = verifyPassword(row.password(), rawPassword);
        boolean enabled = "0".equals(row.status()) && "0".equals(row.delFlag());
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
        if (loginName == null) {
            return null;
        }
        String trimmed = loginName.trim();
        if (trimmed.isEmpty() || trimmed.length() > 64) {
            return null;
        }
        for (int index = 0; index < trimmed.length(); index++) {
            if (trimmed.charAt(index) > 0x7F) {
                return null;
            }
        }
        return trimmed.toLowerCase(Locale.ROOT);
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

    private record AccountRow(
            long userId,
            String loginName,
            String displayName,
            String password,
            String status,
            String delFlag) {}
}
