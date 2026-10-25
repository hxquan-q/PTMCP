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
        r1Baseline(cases);
        systemLibrary(cases);
        functions(cases);
        castAndConversion(cases);
        lockAndConcurrency(cases);
        multiStatement(cases);
        budgetBoundaries(cases);
        columnAmbiguity(cases);
        unionAndCte(cases);
        caseSensitivity(cases);
        dualSchemaSameName(cases);
        injectionAttempts(cases);
        edgeCases(cases);
        return cases;
    }

    // ---- R1 six baseline examples (positive + negative pairs) ----
    private static void r1Baseline(List<TestCaseSpec> cases) {
        cases.add(TestCaseSpec.allow("R1-001", "R1", "SELECT id, name FROM users"));
        cases.add(
                TestCaseSpec.allow(
                        "R1-002", "R1", "SELECT u.id, u.name FROM users u WHERE u.id = 1"));
        cases.add(
                TestCaseSpec.allow(
                        "R1-003",
                        "R1",
                        "SELECT o.id FROM orders o WHERE o.user_id = 1 ORDER BY o.id"));
        cases.add(
                TestCaseSpec.reject(
                        "R1-004",
                        "R1",
                        "INSERT INTO users (id, name) VALUES (1, 'a')",
                        ErrorCode.SQL_NOT_READONLY));
        cases.add(
                TestCaseSpec.reject(
                        "R1-005",
                        "R1",
                        "DELETE FROM users WHERE id = 1",
                        ErrorCode.SQL_NOT_READONLY));
        cases.add(
                TestCaseSpec.reject(
                        "R1-006",
                        "R1",
                        "UPDATE users SET name = 'x' WHERE id = 1",
                        ErrorCode.SQL_NOT_READONLY));
    }

    // ---- System library access ----
    private static void systemLibrary(List<TestCaseSpec> cases) {
        for (int i = 1; i <= 20; i++) {
            cases.add(
                    TestCaseSpec.reject(
                            "SYS-" + String.format("%03d", i),
                            "system_library",
                            "SELECT * FROM mysql." + "table" + i,
                            ErrorCode.TABLE_NOT_AUTHORIZED));
        }
        cases.add(
                TestCaseSpec.reject(
                        "SYS-021",
                        "system_library",
                        "SELECT * FROM information_schema.tables",
                        ErrorCode.TABLE_NOT_AUTHORIZED));
        cases.add(
                TestCaseSpec.reject(
                        "SYS-022",
                        "system_library",
                        "SELECT * FROM information_schema.columns",
                        ErrorCode.TABLE_NOT_AUTHORIZED));
        cases.add(
                TestCaseSpec.reject(
                        "SYS-023",
                        "system_library",
                        "SELECT * FROM performance_schema.events",
                        ErrorCode.TABLE_NOT_AUTHORIZED));
        cases.add(
                TestCaseSpec.reject(
                        "SYS-024",
                        "system_library",
                        "SELECT * FROM sys.sys_config",
                        ErrorCode.TABLE_NOT_AUTHORIZED));
        for (int i = 25; i <= 40; i++) {
            cases.add(
                    TestCaseSpec.reject(
                            "SYS-" + String.format("%03d", i),
                            "system_library",
                            "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = 'shop'",
                            ErrorCode.TABLE_NOT_AUTHORIZED));
        }
    }

    // ---- Function calls ----
    private static void functions(List<TestCaseSpec> cases) {
        String[] allowedFunctions = {
            "COUNT", "SUM", "AVG", "MIN", "MAX", "LOWER", "UPPER", "LENGTH",
            "CONCAT", "COALESCE", "NULLIF", "IFNULL", "ABS", "ROUND", "FLOOR", "CEIL"
        };
        for (int i = 0; i < allowedFunctions.length; i++) {
            cases.add(
                    TestCaseSpec.allow(
                            "FN-" + String.format("%03d", i + 1),
                            "functions",
                            "SELECT " + allowedFunctions[i] + "(id) FROM users"));
        }
        // Variations with arguments
        for (int i = 0; i < 20; i++) {
            cases.add(
                    TestCaseSpec.allow(
                            "FN-" + String.format("%03d", allowedFunctions.length + i + 1),
                            "functions",
                            "SELECT id, name FROM users WHERE "
                                    + allowedFunctions[i % allowedFunctions.length]
                                    + "(id) > "
                                    + i));
        }
        // Subquery + function combinations
        for (int i = 0; i < 15; i++) {
            cases.add(
                    TestCaseSpec.allow(
                            "FN-" + String.format("%03d", allowedFunctions.length + 20 + i + 1),
                            "functions",
                            "SELECT COUNT(*) FROM users WHERE id IN (SELECT id FROM orders WHERE value > "
                                    + i
                                    + ")"));
        }
    }

    // ---- CAST and type conversion ----
    private static void castAndConversion(List<TestCaseSpec> cases) {
        String[] types = {"SIGNED", "UNSIGNED", "CHAR", "DECIMAL(10,2)", "DATE", "DATETIME"};
        for (int i = 0; i < types.length; i++) {
            cases.add(
                    TestCaseSpec.allow(
                            "CAST-" + String.format("%03d", i + 1),
                            "cast",
                            "SELECT CAST(id AS " + types[i] + ") FROM users"));
            cases.add(
                    TestCaseSpec.allow(
                            "CAST-" + String.format("%03d", types.length + i + 1),
                            "cast",
                            "SELECT CAST(name AS CHAR) FROM users WHERE CAST(id AS SIGNED) > 0"));
        }
        for (int i = 0; i < 15; i++) {
            cases.add(
                    TestCaseSpec.allow(
                            "CAST-" + String.format("%03d", types.length * 2 + i + 1),
                            "cast",
                            "SELECT id, CAST(value AS DECIMAL(10,2)) FROM users WHERE CAST(created_at AS DATE) >= '2026-01-01'"));
        }
    }

    // ---- Lock and concurrency syntax ----
    private static void lockAndConcurrency(List<TestCaseSpec> cases) {
        for (int i = 1; i <= 15; i++) {
            cases.add(
                    TestCaseSpec.reject(
                            "LOCK-" + String.format("%03d", i),
                            "locks",
                            "SELECT * FROM users FOR UPDATE",
                            ErrorCode.SQL_NOT_READONLY));
        }
        for (int i = 1; i <= 15; i++) {
            cases.add(
                    TestCaseSpec.reject(
                            "LOCK-" + String.format("%03d", 15 + i),
                            "locks",
                            "SELECT * FROM users LOCK IN SHARE MODE",
                            ErrorCode.SQL_NOT_READONLY));
        }
        for (int i = 1; i <= 10; i++) {
            cases.add(
                    TestCaseSpec.reject(
                            "LOCK-" + String.format("%03d", 30 + i),
                            "locks",
                            "SELECT GET_LOCK('mylock', 10)",
                            ErrorCode.SQL_NOT_READONLY));
        }
    }

    // ---- Multi-statement injection ----
    private static void multiStatement(List<TestCaseSpec> cases) {
        for (int i = 1; i <= 20; i++) {
            cases.add(
                    TestCaseSpec.reject(
                            "MULTI-" + String.format("%03d", i),
                            "multi_statement",
                            "SELECT 1; SELECT " + i,
                            ErrorCode.SQL_NOT_READONLY));
        }
        for (int i = 1; i <= 15; i++) {
            cases.add(
                    TestCaseSpec.reject(
                            "MULTI-" + String.format("%03d", 20 + i),
                            "multi_statement",
                            "SELECT id FROM users; DROP TABLE users",
                            ErrorCode.SQL_NOT_READONLY));
        }
        for (int i = 1; i <= 10; i++) {
            cases.add(
                    TestCaseSpec.reject(
                            "MULTI-" + String.format("%03d", 35 + i),
                            "multi_statement",
                            "SELECT 1; INSERT INTO audit_log VALUES (" + i + ")",
                            ErrorCode.SQL_NOT_READONLY));
        }
    }

    // ---- Budget boundaries ----
    private static void budgetBoundaries(List<TestCaseSpec> cases) {
        cases.add(TestCaseSpec.allow("BUD-001", "budget", "SELECT id FROM users LIMIT 500"));
        cases.add(TestCaseSpec.allow("BUD-002", "budget", "SELECT id FROM users LIMIT 1"));
        cases.add(
                TestCaseSpec.reject(
                        "BUD-003",
                        "budget",
                        "SELECT id FROM users LIMIT 501",
                        ErrorCode.SQL_BUDGET_EXCEEDED));
        cases.add(
                TestCaseSpec.reject(
                        "BUD-004",
                        "budget",
                        "SELECT id FROM users LIMIT 10000",
                        ErrorCode.SQL_BUDGET_EXCEEDED));
        for (int i = 5; i <= 25; i++) {
            int limit = i * 20;
            if (limit <= 500) {
                cases.add(
                        TestCaseSpec.allow(
                                "BUD-" + String.format("%03d", i + 1),
                                "budget",
                                "SELECT id FROM users LIMIT " + limit));
            } else {
                cases.add(
                        TestCaseSpec.reject(
                                "BUD-" + String.format("%03d", i + 1),
                                "budget",
                                "SELECT id FROM users LIMIT " + limit,
                                ErrorCode.SQL_BUDGET_EXCEEDED));
            }
        }
        for (int i = 26; i <= 50; i++) {
            cases.add(
                    TestCaseSpec.allow(
                            "BUD-" + String.format("%03d", i + 1),
                            "budget",
                            "SELECT id FROM users WHERE id > " + i + " LIMIT 100"));
        }
    }

    // ---- Column ambiguity ----
    private static void columnAmbiguity(List<TestCaseSpec> cases) {
        cases.add(
                TestCaseSpec.allow(
                        "AMB-001",
                        "column_ambiguity",
                        "SELECT u.id, o.id FROM users u JOIN orders o ON o.user_id = u.id"));
        cases.add(
                TestCaseSpec.reject(
                        "AMB-002",
                        "column_ambiguity",
                        "SELECT id FROM users u JOIN orders o ON o.user_id = u.id",
                        ErrorCode.SQL_SYNTAX_INVALID));
        for (int i = 3; i <= 20; i++) {
            cases.add(
                    TestCaseSpec.allow(
                            "AMB-" + String.format("%03d", i),
                            "column_ambiguity",
                            "SELECT u.id AS user_id, o.id AS order_id FROM users u JOIN orders o ON o.user_id = u.id WHERE o.value > "
                                    + i));
        }
        for (int i = 21; i <= 35; i++) {
            cases.add(
                    TestCaseSpec.allow(
                            "AMB-" + String.format("%03d", i),
                            "column_ambiguity",
                            "SELECT u.name FROM users u WHERE u.id IN (SELECT o.user_id FROM orders o WHERE o.value >= "
                                    + i
                                    + ")"));
        }
    }

    // ---- UNION and CTE ----
    private static void unionAndCte(List<TestCaseSpec> cases) {
        for (int i = 1; i <= 15; i++) {
            cases.add(
                    TestCaseSpec.allow(
                            "UNI-" + String.format("%03d", i),
                            "union_cte",
                            "SELECT id FROM users UNION SELECT id FROM orders"));
        }
        for (int i = 1; i <= 15; i++) {
            cases.add(
                    TestCaseSpec.allow(
                            "CTE-" + String.format("%03d", i),
                            "union_cte",
                            "WITH cte AS (SELECT id FROM users WHERE id > "
                                    + i
                                    + ") SELECT * FROM cte"));
        }
        for (int i = 1; i <= 10; i++) {
            cases.add(
                    TestCaseSpec.allow(
                            "UNI-" + String.format("%03d", 15 + i),
                            "union_cte",
                            "SELECT u.id FROM users u UNION ALL SELECT o.id FROM orders o LIMIT "
                                    + (i * 10)));
        }
        for (int i = 1; i <= 10; i++) {
            cases.add(
                    TestCaseSpec.allow(
                            "CTE-" + String.format("%03d", 15 + i),
                            "union_cte",
                            "WITH t AS (SELECT id FROM users), s AS (SELECT id FROM orders) SELECT t.id FROM t JOIN s ON t.id = s.id"));
        }
    }

    // ---- Case sensitivity ----
    private static void caseSensitivity(List<TestCaseSpec> cases) {
        for (int i = 1; i <= 15; i++) {
            String suffix = i % 2 == 0 ? "users" : "USERS";
            cases.add(
                    TestCaseSpec.allow(
                            "CASE-" + String.format("%03d", i),
                            "case_sensitivity",
                            "SELECT ID, NAME FROM " + suffix + " WHERE id = " + i));
        }
        for (int i = 1; i <= 15; i++) {
            String fn = i % 3 == 0 ? "count" : i % 3 == 1 ? "COUNT" : "Count";
            cases.add(
                    TestCaseSpec.allow(
                            "CASE-" + String.format("%03d", 15 + i),
                            "case_sensitivity",
                            "SELECT " + fn + "(*) FROM users"));
        }
    }

    // ---- Dual schema same-name tables ----
    private static void dualSchemaSameName(List<TestCaseSpec> cases) {
        for (int i = 1; i <= 15; i++) {
            cases.add(
                    TestCaseSpec.allow(
                            "DUAL-" + String.format("%03d", i),
                            "dual_schema",
                            "SELECT summary.id FROM report.summary WHERE summary.value > " + i));
        }
        for (int i = 1; i <= 15; i++) {
            cases.add(
                    TestCaseSpec.allow(
                            "DUAL-" + String.format("%03d", 15 + i),
                            "dual_schema",
                            "SELECT daily_stats.id FROM report.daily_stats WHERE daily_stats.value = "
                                    + i));
        }
        for (int i = 1; i <= 10; i++) {
            cases.add(
                    TestCaseSpec.allow(
                            "DUAL-" + String.format("%03d", 30 + i),
                            "dual_schema",
                            "SELECT s.id, d.id FROM report.summary s JOIN report.daily_stats d ON s.id = d.id WHERE s.value >= "
                                    + i));
        }
    }

    // ---- SQL injection attempts ----
    private static void injectionAttempts(List<TestCaseSpec> cases) {
        String[] injections = {
            "SELECT * FROM users WHERE id = 1 OR 1=1",
            "SELECT * FROM users WHERE id = 1 UNION SELECT password FROM mysql.user",
            "SELECT * FROM users WHERE name = '' OR '' = ''",
            "SELECT * FROM users WHERE id = 1; DROP TABLE users",
            "SELECT * FROM users WHERE id = 1 -- comment",
            "SELECT * FROM users WHERE name LIKE '%'; DROP TABLE orders; --",
            "SELECT * FROM users INTO OUTFILE '/tmp/data'",
            "SELECT LOAD_FILE('/etc/passwd')",
            "SELECT BENCHMARK(1000000, MD5('test'))",
            "SELECT SLEEP(100) FROM users",
        };
        for (int i = 0; i < injections.length; i++) {
            cases.add(
                    TestCaseSpec.reject(
                            "INJ-" + String.format("%03d", i + 1),
                            "injection",
                            injections[i],
                            ErrorCode.SQL_NOT_READONLY));
        }
        // More injection patterns
        for (int i = 0; i < 15; i++) {
            cases.add(
                    TestCaseSpec.reject(
                            "INJ-" + String.format("%03d", injections.length + i + 1),
                            "injection",
                            "SELECT * FROM users WHERE id = 1 OR '1'='1' AND version"
                                    + " /*"
                                    + i
                                    + "*/",
                            ErrorCode.SQL_NOT_READONLY));
        }
    }

    // ---- Edge cases ----
    private static void edgeCases(List<TestCaseSpec> cases) {
        cases.add(TestCaseSpec.reject("EDGE-001", "edge_cases", "", ErrorCode.SQL_NOT_READONLY));
        cases.add(TestCaseSpec.reject("EDGE-002", "edge_cases", "   ", ErrorCode.SQL_NOT_READONLY));
        cases.add(
                TestCaseSpec.reject(
                        "EDGE-003", "edge_cases", "SELECT", ErrorCode.SQL_NOT_READONLY));
        cases.add(TestCaseSpec.allow("EDGE-004", "edge_cases", "SELECT 1"));
        cases.add(TestCaseSpec.allow("EDGE-005", "edge_cases", "SELECT NULL"));
        cases.add(TestCaseSpec.allow("EDGE-006", "edge_cases", "SELECT ''"));
        cases.add(TestCaseSpec.allow("EDGE-007", "edge_cases", "SELECT DISTINCT id FROM users"));
        cases.add(TestCaseSpec.allow("EDGE-008", "edge_cases", "SELECT ALL id FROM users"));
        for (int i = 9; i <= 30; i++) {
            cases.add(
                    TestCaseSpec.allow(
                            "EDGE-" + String.format("%03d", i),
                            "edge_cases",
                            "SELECT id FROM users GROUP BY id HAVING COUNT(*) > " + (i - 8)));
        }
        for (int i = 31; i <= 50; i++) {
            cases.add(
                    TestCaseSpec.allow(
                            "EDGE-" + String.format("%03d", i),
                            "edge_cases",
                            "SELECT id FROM users WHERE name LIKE '%test%' AND id < "
                                    + i
                                    + " ORDER BY id DESC LIMIT 100"));
        }
        for (int i = 51; i <= 70; i++) {
            cases.add(
                    TestCaseSpec.allow(
                            "EDGE-" + String.format("%03d", i),
                            "edge_cases",
                            "SELECT u.id, u.name FROM users u LEFT JOIN orders o ON o.user_id = u.id WHERE o.id IS NULL AND u.id < "
                                    + i));
        }
    }
}
