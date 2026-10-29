package local.ptmcp.authz.oauth;

public record AuthenticatedAccount(
        long userId, String loginName, String displayName, long authVersion) {}
