package local.ptmcp.authz.oauth;

/** Internal authorization attribute names shared by the stores and the JWT customizer. */
final class OAuthAttributes {
    static final String FAMILY_ID = "ptmcp.authz.family_id";
    static final String AUTH_VERSION = "ptmcp.authz.auth_version";

    /** Client-supplied state; the state column holds the server-generated consent state. */
    static final String CLIENT_STATE = "ptmcp.authz.client_state";

    static final long INITIAL_AUTH_VERSION = 1L;

    private OAuthAttributes() {}
}
