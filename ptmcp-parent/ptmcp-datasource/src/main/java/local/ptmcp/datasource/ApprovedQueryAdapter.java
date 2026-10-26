package local.ptmcp.datasource;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.common.execution.ApprovedSqlPlan;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Objects;

/**
 * The single bridge from a guard-approved plan to the read-only executor; this class is the only
 * caller of the package-private {@link ReadOnlyRequest} factory, so no module outside this package
 * can execute a SQL string that did not pass the guard. Before any pool checkout it re-verifies the
 * plan's integrity (hash over the final SQL), its approval window and the caller's authoritative
 * state through the checkout callback; a failure aborts with no business connection.
 */
public final class ApprovedQueryAdapter {
    /** Authoritative pre-checkout revalidation; throws {@link DomainException} to abort. */
    @FunctionalInterface
    public interface CheckoutGuard {
        void beforeCheckout(long datasourceId);
    }

    private final MysqlReadOnlyExecutor executor;
    private final Clock clock;

    public ApprovedQueryAdapter(MysqlReadOnlyExecutor executor) {
        this(executor, Clock.systemUTC());
    }

    public ApprovedQueryAdapter(MysqlReadOnlyExecutor executor, Clock clock) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ReadOnlyResult execute(
            ApprovedSqlPlan plan, CheckoutGuard guard, ReadOnlyCancellation cancellation) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(guard, "guard");
        Objects.requireNonNull(cancellation, "cancellation");
        long datasourceId = plan.datasourceId().value();
        java.time.Instant now = clock.instant();
        if (now.isBefore(plan.approvedAt()) || now.isAfter(plan.expiresAt())) {
            throw new DomainException(ErrorCode.RESOURCE_VERSION_CHANGED);
        }
        if (!hashIntact(plan)) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR);
        }
        // The last authoritative check happens after the approval window check and immediately
        // before the pool checkout; nothing between here and JDBC can widen authorization.
        guard.beforeCheckout(datasourceId);
        ReadOnlyRequest request = ReadOnlyRequest.of(plan.finalSql(), plan.effectiveLimit());
        return executor.execute(datasourceId, request, cancellation);
    }

    private static boolean hashIntact(ApprovedSqlPlan plan) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR);
        }
        byte[] computed = digest.digest(plan.finalSql().getBytes(StandardCharsets.UTF_8));
        return MessageDigest.isEqual(computed, plan.sqlHash());
    }
}
