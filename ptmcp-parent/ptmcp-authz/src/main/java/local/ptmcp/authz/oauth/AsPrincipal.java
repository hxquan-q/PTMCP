package local.ptmcp.authz.oauth;

import java.io.Serializable;
import java.security.Principal;

/** Minimal resource-owner identity; never carries a password, token or ORM entity. */
public record AsPrincipal(long userId, String loginName, String displayName)
        implements Principal, Serializable {

    @Override
    public String getName() {
        return loginName;
    }
}
