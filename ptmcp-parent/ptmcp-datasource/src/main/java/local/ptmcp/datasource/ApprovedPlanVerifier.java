package local.ptmcp.datasource;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.common.execution.ApprovedSqlPlan;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;

/**
 * Re-validates the common approved-plan seam before any business pool checkout. Both engine
 * adapters share this check so a plan cannot be replayed after its window or altered between the
 * guard and JDBC.
 */
public final class ApprovedPlanVerifier {
    private ApprovedPlanVerifier() {}

    public static void verify(ApprovedSqlPlan plan, Clock clock) {
        java.time.Instant now = clock.instant();
        if (now.isBefore(plan.approvedAt()) || now.isAfter(plan.expiresAt())) {
            throw new DomainException(ErrorCode.RESOURCE_VERSION_CHANGED);
        }
        if (!hashIntact(plan)) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR);
        }
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
