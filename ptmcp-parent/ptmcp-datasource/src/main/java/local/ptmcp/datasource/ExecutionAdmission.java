package local.ptmcp.datasource;

import java.util.UUID;

/**
 * The gate between an approved request and the business connection pool: audit START admission and
 * the security-state freshness check must both confirm before a business connection is borrowed
 * (db-design §7 — 审计准入之后才借业务连接). Implementations come from the persistence wiring; a refusal here
 * must leave the physical connection count untouched.
 *
 * <p>{@link #internalPermissive} exists only for module-internal scaffolding (T10-a tests and
 * factory paths that never see approved traffic); production wiring must inject a real gate.
 */
@FunctionalInterface
public interface ExecutionAdmission {
    void requireAdmission(long datasourceId, UUID executionId);

    static ExecutionAdmission internalPermissive() {
        return (datasourceId, executionId) -> {};
    }
}
