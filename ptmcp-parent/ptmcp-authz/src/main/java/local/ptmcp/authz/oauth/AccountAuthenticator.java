package local.ptmcp.authz.oauth;

import java.util.Optional;

/** Boundary between the AS and the external identity adapter. */
public interface AccountAuthenticator {
    Optional<AuthenticatedAccount> authenticate(String loginName, String rawPassword);
}
