package local.ptmcp.guard.approved;

import local.ptmcp.common.resource.ResourceId;
import local.ptmcp.common.resource.ResourceRef;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The signed-off result of a successful guard run. Construction is package-private: only {@link
 * GuardPipeline#guard} inside this package can produce an instance after every stage has passed.
 * There is no public constructor, no setter and no deserialization entry point, so a caller cannot
 * assemble one from untrusted data.
 *
 * <p>Holding an instance authorizes nothing by itself. The executor must re-verify, before checkout
 * and again before JDBC commit: the query is not expired, the subject and platform match, the hash
 * is intact, the snapshot/resource versions still match authoritative state and the authoritative
 * security check is fresh. {@link #finalSql()} is the only text that may reach JDBC; the original
 * request SQL must never be executed.
 */
public final class ApprovedQuery {
    /** Transient approval window; the executor must re-verify current state within it. */
    static final Duration APPROVAL_WINDOW = Duration.ofSeconds(10);

    private final UUID requestId;
    private final long principalId;
    private final long platformId;
    private final ResourceId datasourceId;
    private final long configVersion;
    private final String catalog;
    private final String schema;
    private final ResourceId snapshotId;
    private final long resourceVersion;
    private final long policyEpoch;
    private final long authVersion;
    private final List<ResourceRef> resourceRefs;
    private final String finalSql;
    private final byte[] sqlHash;
    private final int effectiveLimit;
    private final String guardVersion;
    private final Instant approvedAt;
    private final Instant expiresAt;

    ApprovedQuery(
            UUID requestId,
            GuardContext context,
            List<ResourceRef> resourceRefs,
            String finalSql,
            byte[] sqlHash,
            int effectiveLimit,
            String guardVersion,
            Instant approvedAt) {
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.principalId = context.principalId();
        this.platformId = context.platformId();
        this.datasourceId = context.datasourceId();
        this.configVersion = context.configVersion();
        this.catalog = context.catalog();
        this.schema = context.schema();
        this.snapshotId = context.snapshotId();
        this.resourceVersion = context.resourceVersion();
        this.policyEpoch = context.policyEpoch();
        this.authVersion = context.authVersion();
        this.resourceRefs = List.copyOf(resourceRefs);
        this.finalSql = Objects.requireNonNull(finalSql, "finalSql");
        this.sqlHash = Objects.requireNonNull(sqlHash, "sqlHash").clone();
        this.effectiveLimit = effectiveLimit;
        this.guardVersion = Objects.requireNonNull(guardVersion, "guardVersion");
        this.approvedAt = Objects.requireNonNull(approvedAt, "approvedAt");
        this.expiresAt = approvedAt.plus(APPROVAL_WINDOW);
    }

    public UUID requestId() {
        return requestId;
    }

    public long principalId() {
        return principalId;
    }

    public long platformId() {
        return platformId;
    }

    public ResourceId datasourceId() {
        return datasourceId;
    }

    public long configVersion() {
        return configVersion;
    }

    public String catalog() {
        return catalog;
    }

    public String schema() {
        return schema;
    }

    public ResourceId snapshotId() {
        return snapshotId;
    }

    public long resourceVersion() {
        return resourceVersion;
    }

    public long policyEpoch() {
        return policyEpoch;
    }

    public long authVersion() {
        return authVersion;
    }

    public List<ResourceRef> resourceRefs() {
        return resourceRefs;
    }

    public String finalSql() {
        return finalSql;
    }

    public byte[] sqlHash() {
        return sqlHash.clone();
    }

    public int effectiveLimit() {
        return effectiveLimit;
    }

    public String guardVersion() {
        return guardVersion;
    }

    public Instant approvedAt() {
        return approvedAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    /**
     * Usable only inside the approval window: false once expired. Freshness of the authoritative
     * security check remains the executor's separate duty before checkout and commit.
     */
    public boolean isUsable(Instant now) {
        Objects.requireNonNull(now, "now");
        return !now.isBefore(approvedAt) && !now.isAfter(expiresAt);
    }
}
