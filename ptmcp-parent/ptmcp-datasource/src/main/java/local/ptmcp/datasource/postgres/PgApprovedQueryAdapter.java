package local.ptmcp.datasource.postgres;

import local.ptmcp.common.execution.ApprovedSqlPlan;
import local.ptmcp.datasource.ApprovedPlanVerifier;
import local.ptmcp.datasource.ApprovedQueryAdapter;
import local.ptmcp.datasource.ReadOnlyCancellation;

import java.time.Clock;
import java.util.Objects;

import javax.sql.DataSource;

/**
 * The PostgreSQL counterpart of the MySQL approved-plan bridge. It only accepts the common plan
 * seam, re-verifies its hash/window, runs the caller's authoritative pre-checkout check and then
 * uses the package-private PG request factory; no arbitrary SQL string can reach JDBC.
 */
public final class PgApprovedQueryAdapter {
    private final PgExecutorSettings settings;
    private final Clock clock;

    public PgApprovedQueryAdapter() {
        this(PgExecutorSettings.defaults(), Clock.systemUTC());
    }

    public PgApprovedQueryAdapter(PgExecutorSettings settings, Clock clock) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public PgReadOnlyResult execute(
            ApprovedSqlPlan plan,
            DataSource dataSource,
            long poolGeneration,
            ApprovedQueryAdapter.CheckoutGuard guard,
            ReadOnlyCancellation cancellation) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(guard, "guard");
        Objects.requireNonNull(cancellation, "cancellation");
        ApprovedPlanVerifier.verify(plan, clock);
        guard.beforeCheckout(plan.datasourceId().value());
        PgReadOnlyRequest request = PgReadOnlyRequest.of(plan.finalSql(), plan.effectiveLimit());
        return new PgReadOnlyExecutor(dataSource, settings)
                .execute(poolGeneration, request, cancellation);
    }
}
