package local.ptmcp.regression;

import local.ptmcp.common.error.ErrorCode;

/** Expected outcome for one synthetic regression case. */
public record TestCaseSpec(
        String caseId, String category, String sql, Outcome outcome, ErrorCode expectedCode) {

    public enum Outcome {
        ALLOW,
        REJECT
    }

    public static TestCaseSpec allow(String caseId, String category, String sql) {
        return new TestCaseSpec(caseId, category, sql, Outcome.ALLOW, null);
    }

    public static TestCaseSpec reject(String caseId, String category, String sql, ErrorCode code) {
        return new TestCaseSpec(caseId, category, sql, Outcome.REJECT, code);
    }
}
