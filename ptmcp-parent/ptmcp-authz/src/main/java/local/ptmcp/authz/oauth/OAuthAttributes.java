package local.ptmcp.authz.oauth;

/** Internal authorization attribute names shared by the stores and the JWT customizer. */
final class OAuthAttributes {
    static final String FAMILY_ID = "ptmcp.authz.family_id";
    static final String AUTH_VERSION = "ptmcp.authz.auth_version";

    /** Hash of the refresh token presented to this request; never the token itself. */
    static final String REFRESH_TOKEN_HASH = "ptmcp.authz.refresh_token_hash";

    /** Client-supplied state; the state column holds the server-generated consent state. */
    static final String CLIENT_STATE = "ptmcp.authz.client_state";


    private OAuthAttributes() {}
}
