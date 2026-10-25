package local.ptmcp.regression;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.List;

/** Verifies the manifest has ≥300 cases covering all required categories. */
class ManifestCountTest {
    @Test
    void manifestHasAtLeast300Cases() {
        List<TestCaseSpec> cases = ManifestGenerator.generate();
        assertTrue(cases.size() >= 300, "manifest has " + cases.size() + " cases, need ≥300");
    }

    @Test
    void manifestCoversAllRequiredCategories() {
        List<TestCaseSpec> cases = ManifestGenerator.generate();
        for (String category :
                List.of(
                        "R1",
                        "system_library",
                        "functions",
                        "cast",
                        "locks",
                        "multi_statement",
                        "budget",
                        "column_ambiguity",
                        "union_cte",
                        "case_sensitivity",
                        "dual_schema",
                        "injection",
                        "edge_cases")) {
            assertTrue(
                    cases.stream().anyMatch(c -> c.category().equals(category)),
                    "missing category: " + category);
        }
    }

    @Test
    void manifestHasBothAllowAndRejectCases() {
        List<TestCaseSpec> cases = ManifestGenerator.generate();
        assertTrue(cases.stream().anyMatch(c -> c.outcome() == TestCaseSpec.Outcome.ALLOW));
        assertTrue(cases.stream().anyMatch(c -> c.outcome() == TestCaseSpec.Outcome.REJECT));
    }
}
