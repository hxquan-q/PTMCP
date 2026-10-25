package local.ptmcp.regression;

import java.util.List;

/** Aggregated report for one regression run. */
public record RegressionReport(int total, int passed, int failed, List<CaseResult> results) {
    public RegressionReport {
        results = List.copyOf(results);
    }

    public static RegressionReport of(List<CaseResult> results) {
        int passed = 0;
        int failed = 0;
        for (CaseResult result : results) {
            if (result.passed()) {
                passed++;
            } else {
                failed++;
            }
        }
        return new RegressionReport(results.size(), passed, failed, results);
    }
}
