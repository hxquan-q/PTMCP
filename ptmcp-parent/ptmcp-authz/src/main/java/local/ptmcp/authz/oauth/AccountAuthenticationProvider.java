package local.ptmcp.authz.oauth;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

/** Password login for the AS form, driven by the read-only identity adapter. */
public final class AccountAuthenticationProvider implements AuthenticationProvider {
    private final AccountAuthenticator authenticator;

    public AccountAuthenticationProvider(AccountAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    public Authentication authenticate(Authentication authentication)
            throws AuthenticationException {
        String loginName = authentication.getPrincipal() instanceof String value ? value : null;
        String password = authentication.getCredentials() instanceof String value ? value : null;
        if (!StringUtils.hasText(loginName)) {
            throw new BadCredentialsException("Bad credentials");
        }
        Optional<AuthenticatedAccount> account = authenticator.authenticate(loginName, password);
        if (account.isEmpty()) {
            throw new BadCredentialsException("Bad credentials");
        }
        AuthenticatedAccount value = account.get();
        AsPrincipal principal =
                new AsPrincipal(value.userId(), value.loginName(), value.displayName());
        return UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
