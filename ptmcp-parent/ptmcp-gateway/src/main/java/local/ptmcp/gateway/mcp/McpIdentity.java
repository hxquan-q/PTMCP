package local.ptmcp.gateway.mcp;

import java.time.Instant;
import java.util.Set;

/** Verified server-side identity; never contains bearer or refresh plaintext. */
public record McpIdentity(
        long userId,
        long platformId,
        long authVersion,
        String credentialId,
        String clientId,
        Set<String> scopes,
        Instant expiresAt,
        long policyEpoch,
        Instant checkedAt) {
    public McpIdentity {
        scopes = Set.copyOf(scopes);
    }
}
