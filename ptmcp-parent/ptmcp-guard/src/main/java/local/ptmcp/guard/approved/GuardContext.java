package local.ptmcp.guard.approved;

import local.ptmcp.common.resource.ResourceId;

import java.time.Instant;
import java.util.Objects;

/**
 * Server-verified subject and resource context for one guard run. Every field must be supplied by
 * the caller from values it has already verified against authoritative state; this type does NOT
 * verify authenticity, freshness or consistency between fields. <b>The constructor caller is
 * responsible</b> for the truth of each value — principal/platform identity, config and resource
 * versions, snapshot identity and the authoritative-check timestamp.
 *
 * <p>There is deliberately no factory from HTTP/JSON input, no deserialization binding and no
 * partial-update builder: web and MCP adapters must map each field explicitly from server-side
 * state. A value here is input evidence, never a permission decision.
 */
public record GuardContext(
        long principalId,
        long platformId,
        ResourceId datasourceId,
        long configVersion,
        String catalog,
        String schema,
        ResourceId snapshotId,
        long resourceVersion,
        long policyEpoch,
        long authVersion,
        Instant authoritativelyCheckedAt) {
    public GuardContext {
        Objects.requireNonNull(datasourceId, "datasourceId");
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(catalog, "catalog");
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(authoritativelyCheckedAt, "authoritativelyCheckedAt");
        if (principalId <= 0 || platformId <= 0) {
            throw new IllegalArgumentException("principalId and platformId must be positive");
        }
        if (configVersion <= 0 || resourceVersion <= 0 || authVersion <= 0) {
            throw new IllegalArgumentException("versions must be positive");
        }
        if (policyEpoch < 0) {
            throw new IllegalArgumentException("policyEpoch must not be negative");
        }
    }
}
