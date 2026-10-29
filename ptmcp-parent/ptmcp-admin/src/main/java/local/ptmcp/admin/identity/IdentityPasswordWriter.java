package local.ptmcp.admin.identity;

import local.ptmcp.persistence.security.HostIdentityStore;

import javax.sql.DataSource;

/** Host-side credential adapter; writes always use the immutable operation's hash CAS. */
public final class IdentityPasswordWriter {
    private final HostIdentityStore host;

    public IdentityPasswordWriter(DataSource identity) {
        this.host = new HostIdentityStore(identity);
    }

    HostIdentityStore host() {
        return host;
    }

    public boolean accountExists(long userId) {
        HostIdentityStore.Account account = host.read(userId);
        return account != null && account.active();
    }
}
