package local.ptmcp.common.execution;

import local.ptmcp.common.resource.ResourceId;

import java.time.Instant;

/**
 * The closed execution plan the guard hands to the safe executor through this common seam. The
 * datasource module never depends on the guard, so this interface is the only shape it accepts: it
 * deliberately carries no request SQL, no subject identity and no authorization data, and it is
 * implemented only by the guard's approved query. Constructing an implementation from untrusted
 * input is a caller-side contract violation; the executor re-verifies the hash, the approval window
 * and authoritative state before it opens a business connection.
 */
public interface ApprovedSqlPlan {
    /** Physical datasource identity the plan was approved for. */
    ResourceId datasourceId();

    /** Exact datasource configuration approved by the guard; never use a newer current pool. */
    long configVersion();

    /** The only SQL text that may reach JDBC; it is never the caller's original string. */
    String finalSql();

    /** SHA-256 of {@link #finalSql()} in UTF-8; the executor re-computes and compares it. */
    byte[] sqlHash();

    /** Row budget already tightened by the guard; between 1 and the engine limit. */
    int effectiveLimit();

    Instant approvedAt();

    /** Transient approval window end; {@link #approvedAt()} to this instant bounds usability. */
    Instant expiresAt();
}
