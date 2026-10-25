package local.ptmcp.regression;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.guard.approved.ApprovedQuery;

import java.time.Duration;
import java.time.Instant;

/** The result of running one synthetic case through the guard pipeline. */
public record CaseResult(
        String caseId,
        String category,
        String sql,
        String expected,
        String actual,
        boolean passed,
        long durationMillis,
        String failureReason) {

    public static CaseResult pass(TestCaseSpec spec, long durationMillis) {
        return new CaseResult(
                spec.caseId(),
                spec.category(),
                spec.sql(),
                spec.outcome().name(),
                spec.outcome().name(),
                true,
                durationMillis,
                null);
    }

    public static CaseResult fail(TestCaseSpec spec, String actual, String reason) {
        return new CaseResult(
                spec.caseId(),
                spec.category(),
                spec.sql(),
                spec.outcome().name(),
                actual,
                false,
                0,
                reason);
    }

    public static CaseResult from(TestCaseSpec spec, ApprovedQuery approved, Instant start) {
        long elapsed = Duration.between(start, Instant.now()).toMillis();
        if (spec.outcome() == TestCaseSpec.Outcome.ALLOW) {
            return new CaseResult(
                    spec.caseId(),
                    spec.category(),
                    spec.sql(),
                    "ALLOW",
                    "ALLOW",
                    true,
                    elapsed,
                    null);
        }
        return new CaseResult(
                spec.caseId(),
                spec.category(),
                spec.sql(),
                "REJECT",
                "ALLOW",
                false,
                elapsed,
                "expected rejection but got approval");
    }

    public static CaseResult from(TestCaseSpec spec, DomainException error, Instant start) {
        long elapsed = Duration.between(start, Instant.now()).toMillis();
        if (spec.outcome() == TestCaseSpec.Outcome.REJECT) {
            if (spec.expectedCode() != null && error.errorCode() == spec.expectedCode()) {
                return new CaseResult(
                        spec.caseId(),
                        spec.category(),
                        spec.sql(),
                        "REJECT:" + spec.expectedCode().name(),
                        "REJECT:" + error.errorCode().name(),
                        true,
                        elapsed,
                        null);
            }
            return new CaseResult(
                    spec.caseId(),
                    spec.category(),
                    spec.sql(),
                    "REJECT:" + (spec.expectedCode() == null ? "ANY" : spec.expectedCode().name()),
                    "REJECT:" + error.errorCode().name(),
                    false,
                    elapsed,
                    "error code mismatch: expected "
                            + (spec.expectedCode() == null ? "any" : spec.expectedCode().name())
                            + " but got "
                            + error.errorCode().name());
        }
        return new CaseResult(
                spec.caseId(),
                spec.category(),
                spec.sql(),
                "ALLOW",
                "REJECT:" + error.errorCode().name(),
                false,
                elapsed,
                "expected allow but got rejection: " + error.errorCode().name());
    }
}
