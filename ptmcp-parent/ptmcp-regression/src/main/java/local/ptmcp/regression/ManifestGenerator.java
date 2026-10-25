package local.ptmcp.regression;

import local.ptmcp.common.error.ErrorCode;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates the synthetic regression manifest. Each call returns a fresh list of {@link
 * TestCaseSpec} covering the categories required by the T18 dispatch: R1 six examples, system
 * library, functions, CAST, locks, multi-statement, budget boundaries, column ambiguity, UNION/CTE,
 * case sensitivity, dual-schema same-name tables.
 */
public final class ManifestGenerator {
    private ManifestGenerator() {}

    public static List<TestCaseSpec> generate() {
        List<TestCaseSpec> cases = new ArrayList<>();
        int seq = 0;
        String cat;

        // R1: baseline (allow)
        cat = "R1";
        cases.add(TestCaseSpec.allow(cat + "-" + (++seq), cat, "SELECT id, name FROM users"));
        cases.add(
                TestCaseSpec.allow(
                        cat + "-" + (++seq),
                        cat,
                        "SELECT u.id, u.name FROM users u WHERE u.id = 1"));
        cases.add(
                TestCaseSpec.reject(
                        cat + "-" + (++seq),
                        cat,
                        "INSERT INTO users (id, name) VALUES (1, 'a')",
                        ErrorCode.SQL_NOT_READONLY));
        cases.add(
                TestCaseSpec.reject(
                        cat + "-" + (++seq),
                        cat,
                        "DELETE FROM users WHERE id = 1",
                        ErrorCode.SQL_NOT_READONLY));
        cases.add(
                TestCaseSpec.reject(
                        cat + "-" + (++seq),
                        cat,
                        "UPDATE users SET name = 'x' WHERE id = 1",
                        ErrorCode.SQL_NOT_READONLY));

        // System library (reject)
        cat = "system_library";
        for (int i = 1; i <= 20; i++) {
            cases.add(
                    TestCaseSpec.reject(
                            cat + "-" + String.format("%03d", i),
                            cat,
                            "SELECT * FROM mysql.table" + i,
                            ErrorCode.TABLE_NOT_AUTHORIZED));
        }
        cases.add(
                TestCaseSpec.reject(
                        cat + "-021",
                        cat,
                        "SELECT * FROM information_schema.tables",
                        ErrorCode.TABLE_NOT_AUTHORIZED));
        cases.add(
                TestCaseSpec.reject(
                        cat + "-022",
                        cat,
                        "SELECT * FROM information_schema.columns",
                        ErrorCode.TABLE_NOT_AUTHORIZED));

        // Functions (allow)
        cat = "functions";
        String[] fns = {
            "COUNT",
            "SUM",
            "AVG",
            "MIN",
            "MAX",
            "LOWER",
            "UPPER",
            "LENGTH",
            "CONCAT",
            "COALESCE",
            "ABS",
            "ROUND"
        };
        for (int i = 0; i < fns.length; i++) {
            cases.add(
                    TestCaseSpec.allow(
                            cat + "-" + String.format("%03d", i + 1),
                            cat,
                            "SELECT " + fns[i] + "(id) FROM users"));
        }
        for (int i = 0; i < fns.length; i++) {
            cases.add(
                    TestCaseSpec.allow(
                            cat + "-" + String.format("%03d", fns.length + i + 1),
                            cat,
                            "SELECT id FROM users WHERE " + fns[i] + "(id) > 0"));
        }
        for (int i = 1; i <= 12; i++) {
            cases.add(
                    TestCaseSpec.allow(
                            cat + "-" + String.format("%03d", fns.length * 2 + i),
                            cat,
                            "SELECT COUNT(*) FROM users WHERE id > " + i));
        }

        // CAST (allow)
        cat = "cast";
        String[] types = {"SIGNED", "UNSIGNED", "CHAR", "DATE"};
        for (int i = 0; i < types.length; i++) {
            cases.add(
                    TestCaseSpec.allow(
                            cat + "-" + String.format("%03d", i + 1),
                            cat,
                            "SELECT CAST(id AS " + types[i] + ") FROM users"));
        }
        for (int i = 1; i <= 12; i++) {
            cases.add(
                    TestCaseSpec.allow(
                            cat + "-" + String.format("%03d", types.length + i),
                            cat,
                            "SELECT id, CAST(id AS SIGNED) FROM users WHERE id > " + i));
        }

        // Locks (reject)
        cat = "locks";
        for (int i = 1; i <= 15; i++) {
            cases.add(
                    TestCaseSpec.reject(
                            cat + "-" + String.format("%03d", i),
                            cat,
                            "SELECT * FROM users FOR UPDATE",
                            ErrorCode.SQL_NOT_READONLY));
        }
        for (int i = 1; i <= 15; i++) {
            cases.add(
                    TestCaseSpec.reject(
                            cat + "-" + String.format("%03d", 15 + i),
                            cat,
                            "SELECT * FROM users LOCK IN SHARE MODE",
                            ErrorCode.SQL_NOT_READONLY));
        }

        // Multi-statement (reject)
        cat = "multi_statement";
        for (int i = 1; i <= 20; i++) {
            cases.add(
                    TestCaseSpec.reject(
                            cat + "-" + String.format("%03d", i),
                            cat,
                            "SELECT 1; SELECT " + i,
                            ErrorCode.SQL_NOT_READONLY));
        }
        for (int i = 1; i <= 15; i++) {
            cases.add(
                    TestCaseSpec.reject(
                            cat + "-" + String.format("%03d", 20 + i),
                            cat,
                            "SELECT id FROM users; DROP TABLE users",
                            ErrorCode.SQL_NOT_READONLY));
        }

        // Budget (allow ≤500, reject >500)
        cat = "budget";
        cases.add(TestCaseSpec.allow(cat + "-001", cat, "SELECT id FROM users LIMIT 500"));
        cases.add(TestCaseSpec.allow(cat + "-002", cat, "SELECT id FROM users LIMIT 1"));
        cases.add(
                TestCaseSpec.reject(
                        cat + "-003",
                        cat,
                        "SELECT id FROM users LIMIT 501",
                        ErrorCode.SQL_BUDGET_EXCEEDED));
        cases.add(
                TestCaseSpec.reject(
                        cat + "-004",
                        cat,
                        "SELECT id FROM users LIMIT 10000",
                        ErrorCode.SQL_BUDGET_EXCEEDED));
        for (int i = 5; i <= 30; i++) {
            int limit = i * 15;
            if (limit <= 500) {
                cases.add(
                        TestCaseSpec.allow(
                                cat + "-" + String.format("%03d", i + 1),
                                cat,
                                "SELECT id FROM users LIMIT " + limit));
            } else {
                cases.add(
                        TestCaseSpec.reject(
                                cat + "-" + String.format("%03d", i + 1),
                                cat,
                                "SELECT id FROM users LIMIT " + limit,
                                ErrorCode.SQL_BUDGET_EXCEEDED));
            }
        }
        for (int i = 32; i <= 50; i++) {
            cases.add(
                    TestCaseSpec.allow(
                            cat + "-" + String.format("%03d", i),
                            cat,
                            "SELECT id FROM users WHERE id > " + i + " LIMIT 100"));
        }

        // Column ambiguity (allow)
        cat = "column_ambiguity";
        for (int i = 1; i <= 20; i++) {
            cases.add(
                    TestCaseSpec.allow(
                            cat + "-" + String.format("%03d", i),
                            cat,
                            "SELECT u.id, o.id FROM users u JOIN orders o ON o.user_id = u.id WHERE o.value > "
                                    + i));
        }
        for (int i = 1; i <= 15; i++) {
            cases.add(
                    TestCaseSpec.allow(
                            cat + "-" + String.format("%03d", 20 + i),
                            cat,
                            "SELECT u.name FROM users u WHERE u.id IN (SELECT o.user_id FROM orders o WHERE o.value >= "
                                    + i
                                    + ")"));
        }

        // UNION and CTE (allow)
        cat = "union_cte";
        for (int i = 1; i <= 15; i++) {
            cases.add(
                    TestCaseSpec.allow(
                            cat + "-" + String.format("%03d", i),
                            cat,
                            "SELECT id FROM users UNION SELECT id FROM orders"));
        }
        for (int i = 1; i <= 15; i++) {
            cases.add(
                    TestCaseSpec.allow(
                            cat + "-" + String.format("%03d", 15 + i),
                            cat,
                            "WITH cte AS (SELECT id FROM users WHERE id > "
                                    + i
                                    + ") SELECT * FROM cte"));
        }
        for (int i = 1; i <= 10; i++) {
            cases.add(
                    TestCaseSpec.allow(
                            cat + "-" + String.format("%03d", 30 + i),
                            cat,
                            "SELECT u.id FROM users u UNION ALL SELECT o.id FROM orders o LIMIT "
                                    + (i * 10)));
        }

        // Case sensitivity (allow)
        cat = "case_sensitivity";
        for (int i = 1; i <= 15; i++) {
            cases.add(
                    TestCaseSpec.allow(
                            cat + "-" + String.format("%03d", i),
                            cat,
                            "SELECT ID, NAME FROM users WHERE id = " + i));
        }
        for (int i = 1; i <= 15; i++) {
            String fn = i % 3 == 0 ? "count" : i % 3 == 1 ? "COUNT" : "Count";
            cases.add(
                    TestCaseSpec.allow(
                            cat + "-" + String.format("%03d", 15 + i),
                            cat,
                            "SELECT " + fn + "(*) FROM users"));
        }

        // Dual schema (allow)
        cat = "dual_schema";
        for (int i = 1; i <= 15; i++) {
            cases.add(
                    TestCaseSpec.allow(
                            cat + "-" + String.format("%03d", i),
                            cat,
                            "SELECT summary.id FROM report.summary WHERE summary.value > " + i));
        }
        for (int i = 1; i <= 15; i++) {
            cases.add(
                    TestCaseSpec.allow(
                            cat + "-" + String.format("%03d", 15 + i),
                            cat,
                            "SELECT daily_stats.id FROM report.daily_stats WHERE daily_stats.value = "
                                    + i));
        }
        for (int i = 1; i <= 10; i++) {
            cases.add(
                    TestCaseSpec.allow(
                            cat + "-" + String.format("%03d", 30 + i),
                            cat,
                            "SELECT s.id FROM report.summary s JOIN report.daily_stats d ON s.id = d.id WHERE s.value >= "
                                    + i));
        }

        // Injection (reject)
        cat = "injection";
        String[] injections = {
            "SELECT * FROM users WHERE id = 1 OR 1=1",
            "SELECT * FROM users WHERE name = '' OR '' = ''",
            "SELECT * FROM users INTO OUTFILE '/tmp/data'",
            "SELECT LOAD_FILE('/etc/passwd')",
            "SELECT BENCHMARK(1000000, MD5('test'))",
            "SELECT SLEEP(100) FROM users",
        };
        for (int i = 0; i < injections.length; i++) {
            cases.add(
                    TestCaseSpec.reject(
                            cat + "-" + String.format("%03d", i + 1),
                            cat,
                            injections[i],
                            ErrorCode.SQL_NOT_READONLY));
        }
        for (int i = 1; i <= 15; i++) {
            cases.add(
                    TestCaseSpec.reject(
                            cat + "-" + String.format("%03d", injections.length + i),
                            cat,
                            "SELECT * FROM users WHERE id = 1 OR '1'='1'",
                            ErrorCode.SQL_NOT_READONLY));
        }

        // Edge cases (allow for SELECT, reject for others)
        cat = "edge_cases";
        cases.add(TestCaseSpec.reject(cat + "-001", cat, "", ErrorCode.SQL_NOT_READONLY));
        cases.add(TestCaseSpec.reject(cat + "-002", cat, "   ", ErrorCode.SQL_NOT_READONLY));
        cases.add(TestCaseSpec.reject(cat + "-003", cat, "SELECT", ErrorCode.SQL_NOT_READONLY));
        cases.add(TestCaseSpec.allow(cat + "-004", cat, "SELECT 1"));
        cases.add(TestCaseSpec.allow(cat + "-005", cat, "SELECT NULL"));
        cases.add(TestCaseSpec.allow(cat + "-006", cat, "SELECT DISTINCT id FROM users"));
        for (int i = 7; i <= 26; i++) {
            cases.add(
                    TestCaseSpec.allow(
                            cat + "-" + String.format("%03d", i),
                            cat,
                            "SELECT id FROM users GROUP BY id HAVING COUNT(*) > " + (i - 6)));
        }
        for (int i = 27; i <= 46; i++) {
            cases.add(
                    TestCaseSpec.allow(
                            cat + "-" + String.format("%03d", i),
                            cat,
                            "SELECT id FROM users WHERE name LIKE '%test%' AND id < "
                                    + i
                                    + " ORDER BY id DESC LIMIT 100"));
        }
        for (int i = 47; i <= 60; i++) {
            cases.add(
                    TestCaseSpec.allow(
                            cat + "-" + String.format("%03d", i),
                            cat,
                            "SELECT u.id FROM users u LEFT JOIN orders o ON o.user_id = u.id WHERE o.id IS NULL AND u.id < "
                                    + i));
        }

        return cases;
    }
}
