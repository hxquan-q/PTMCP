package local.ptmcp.guard.lexing;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

class LexicalPrecheckTest {
    private static final SqlLexicalPolicy MYSQL = SqlLexicalPolicy.mysql(false, false, 65536);
    private static final SqlLexicalPolicy PG = SqlLexicalPolicy.postgresql(true, 65536);

    static Stream<SqlLexicalPolicy> policies() {
        return Stream.of(
                MYSQL,
                SqlLexicalPolicy.mysql(true, false, 65536),
                SqlLexicalPolicy.mysql(false, true, 65536),
                SqlLexicalPolicy.mysql(true, true, 65536),
                PG,
                SqlLexicalPolicy.postgresql(false, 65536));
    }

    @ParameterizedTest
    @MethodSource("policies")
    void acceptsOnlyLexicalCompletenessNotReadonlyApproval(SqlLexicalPolicy policy) {
        for (String sql :
                new String[] {
                    "SELECT 1",
                    "SELECT 1; /* ordinary */ -- tail\n",
                    "UPDATE orders SET amount=1",
                    "DELETE FROM orders",
                    "SELECT 'DROP; /*! /*+ \\G'",
                    "SELECT 'it''s data'",
                    "SELECT \"a\"\"b\"",
                    "/* ; */ SELECT 1",
                    "SELECT '中文😀'"
                }) {
            assertDoesNotThrow(() -> LexicalPrecheck.check(sql, policy));
        }
    }

    @ParameterizedTest
    @MethodSource("policies")
    void rejectsOutsideQuotedDataWithoutEcho(SqlLexicalPolicy policy) {
        for (String sql :
                new String[] {
                    "SELECT 1; SELECT 2", "SELECT 1;;", "SELECT 1; 'data'", ";SELECT 1"
                }) {
            rejected(sql, policy, ErrorCode.SQL_NOT_READONLY);
        }
        for (String sql :
                new String[] {
                    "SELECT 1 \\G",
                    "SELECT 1 \\g",
                    "\\copy x",
                    "SELECT /*!123 x */ 1",
                    "SELECT /*+ x */ 1"
                }) {
            rejected(sql, policy, ErrorCode.SQL_UNSUPPORTED);
        }
        for (String sql :
                new String[] {
                    "SELECT 'unterminated",
                    "SELECT \"unterminated",
                    "/* x",
                    "SELECT 'x' /* x",
                    "SELECT 1; /* x",
                    "",
                    " \n /*only*/"
                }) {
            rejected(sql, policy, ErrorCode.SQL_SYNTAX_INVALID);
        }
    }

    static Stream<Arguments> dialectExamples() {
        return Stream.of(
                Arguments.of(MYSQL, "SELECT `a;/*!\\G``b`", null),
                Arguments.of(MYSQL, "SELECT 1; # ; \\G /*!\n", null),
                Arguments.of(PG, "SELECT 1; # ; x", ErrorCode.SQL_NOT_READONLY),
                Arguments.of(PG, "SELECT 1; --no space ; \\G", null),
                Arguments.of(MYSQL, "SELECT 1; --no space", ErrorCode.SQL_NOT_READONLY),
                Arguments.of(MYSQL, "SELECT 1; --\t ; \\G", null),
                Arguments.of(PG, "SELECT /* a /* b */ c */ 1", null),
                Arguments.of(PG, "SELECT /* a /* b */ 1", ErrorCode.SQL_SYNTAX_INVALID),
                Arguments.of(MYSQL, "SELECT 1; /* a /* b */ c */", ErrorCode.SQL_NOT_READONLY),
                Arguments.of(PG, "SELECT $$ ; /*! /*+ \\G ' $$", null),
                Arguments.of(PG, "SELECT $tag$ ; ' $TAG$ $tag$;", null),
                Arguments.of(PG, "SELECT $中文$ ; ' $中文$;", null),
                Arguments.of(PG, "SELECT $tag$ x $TAG$", ErrorCode.SQL_SYNTAX_INVALID),
                Arguments.of(PG, "SELECT foo$tag$;SELECT 2", ErrorCode.SQL_NOT_READONLY),
                Arguments.of(PG, "SELECT E'escaped\\';data'", null),
                Arguments.of(PG, "SELECT e'escaped\\';data'", null),
                Arguments.of(PG, "SELECT 'escaped\\';SELECT 2", ErrorCode.SQL_NOT_READONLY),
                Arguments.of(SqlLexicalPolicy.postgresql(false), "SELECT 'escaped\\';data'", null),
                Arguments.of(MYSQL, "SELECT 'escaped\\';data'", null),
                Arguments.of(
                        SqlLexicalPolicy.mysql(false, true),
                        "SELECT 'escaped\\';SELECT 2",
                        ErrorCode.SQL_NOT_READONLY),
                Arguments.of(MYSQL, "SELECT \"escaped\\\";data\"", null),
                Arguments.of(
                        SqlLexicalPolicy.mysql(true, false),
                        "SELECT \"escaped\\\";SELECT 2",
                        ErrorCode.SQL_NOT_READONLY),
                Arguments.of(PG, "SELECT \"escaped\\\";SELECT 2", ErrorCode.SQL_NOT_READONLY),
                Arguments.of(PG, "SELECT E'x'\n'escaped\\';data'", null),
                Arguments.of(PG, "SELECT E'a' --c\n'b\\';SELECT '2'", ErrorCode.SQL_SYNTAX_INVALID),
                Arguments.of(PG, "SELECT 1 \\G", ErrorCode.SQL_UNSUPPORTED),
                Arguments.of(
                        SqlLexicalPolicy.postgresql(true, 1),
                        "SELECT 1",
                        ErrorCode.SQL_BUDGET_EXCEEDED));
    }

    @ParameterizedTest
    @MethodSource("dialectExamples")
    void respectsVerifiedDialectModes(SqlLexicalPolicy policy, String sql, ErrorCode expected) {
        if (expected == null) {
            assertDoesNotThrow(() -> LexicalPrecheck.check(sql, policy));
        } else {
            rejected(sql, policy, expected);
        }
    }

    @ParameterizedTest
    @MethodSource("policies")
    void validatesUtf8BudgetAndEncodingInEveryState(SqlLexicalPolicy policy) {
        for (String unit : new String[] {"a", "é", "中", "😀"}) {
            int bytes = unit.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            String exact =
                    "'" + unit.repeat((65536 - 2) / bytes) + "a".repeat((65536 - 2) % bytes) + "'";
            assertEquals(65536, exact.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            assertDoesNotThrow(() -> LexicalPrecheck.check(exact, policy));
            rejected(exact + " ", policy, ErrorCode.SQL_BUDGET_EXCEEDED);
        }
        for (String invalid :
                new String[] {"\u0000", "\ud800", "\udc00", "\ud800x", "\udc00\ud800"}) {
            for (String wrapped :
                    new String[] {
                        invalid, "'" + invalid + "'", "/*" + invalid + "*/", "-- " + invalid
                    }) {
                rejected(wrapped, policy, ErrorCode.SQL_SYNTAX_INVALID);
            }
        }
        rejected("a".repeat(1000000), policy, ErrorCode.SQL_BUDGET_EXCEEDED);
        rejected(null, policy, ErrorCode.SQL_SYNTAX_INVALID);
    }

    @Test
    void tightenedBudgetCountsUtf8NotUtf16Units() {
        SqlLexicalPolicy policy = SqlLexicalPolicy.postgresql(true, 6);
        assertDoesNotThrow(() -> LexicalPrecheck.check("'😀'", policy));
        rejected("'😀' ", policy, ErrorCode.SQL_BUDGET_EXCEEDED);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 65537, Integer.MAX_VALUE})
    void cannotRelaxOrDisableServerBudget(int bytes) {
        assertThrows(
                IllegalArgumentException.class, () -> SqlLexicalPolicy.mysql(false, false, bytes));
        assertThrows(
                IllegalArgumentException.class, () -> SqlLexicalPolicy.postgresql(true, bytes));
    }

    @Test
    void repeatedCommentQuoteAndDelimiterMutationsDoNotExposeHiddenStatements() {
        for (String data :
                new String[] {
                    "; DELETE FROM x", "/*! x */", "/*+ x */", "\\G", "-- x", "# x", "/* nested */"
                }) {
            for (SqlLexicalPolicy policy : policies().toList()) {
                assertDoesNotThrow(
                        () -> LexicalPrecheck.check("SELECT '" + data + "'; /* tail */", policy));
                rejected(
                        "SELECT '" + data + "'; /* tail */ SELECT 2",
                        policy,
                        ErrorCode.SQL_NOT_READONLY);
            }
            assertDoesNotThrow(() -> LexicalPrecheck.check("SELECT $t$" + data + "$t$;", PG));
        }
    }

    @Test
    void mysqlNonNestedCommentOpenerMustNotHideAnOverlappingClose() {
        assertDoesNotThrow(() -> LexicalPrecheck.check("SELECT 1; /* /*/", MYSQL));
    }

    @Test
    void mysqlDashCommentRecognizesAsciiDeleteControl() {
        assertDoesNotThrow(() -> LexicalPrecheck.check("SELECT 1; --\u007f ; client text", MYSQL));
    }

    @Test
    void mysqlOverlappingCommentCloseCannotHideAnotherStatement() {
        rejected("SELECT 1; /* /*/ SELECT 2 /* tail */", MYSQL, ErrorCode.SQL_NOT_READONLY);
    }

    @Test
    void postgresEscapeContinuationOnlyCrossesWhitespaceAndLineComments() {
        assertDoesNotThrow(
                () -> LexicalPrecheck.check("SELECT E'x' --tail\n'escaped\\';data'", PG));
        rejected("SELECT E'x' /*\n*/ 'escaped\\';SELECT 2", PG, ErrorCode.SQL_NOT_READONLY);
        assertDoesNotThrow(
                () ->
                        LexicalPrecheck.check(
                                "SELECT B'bits'\n'backslash\\'",
                                SqlLexicalPolicy.postgresql(false)));
        rejected("SELECT identifierE'escaped\\';SELECT 2", PG, ErrorCode.SQL_NOT_READONLY);
    }

    @Test
    void delimiterAndCommentNestingAreBoundedByInputNotJavaCallStack() {
        String tag = "a".repeat(10000);
        assertDoesNotThrow(
                () ->
                        LexicalPrecheck.check(
                                "$" + tag + "$" + "$" + tag + "x" + "$" + tag + "$", PG));
        String nested = "/*".repeat(10000) + "x" + "*/".repeat(10000) + " SELECT 1";
        assertDoesNotThrow(() -> LexicalPrecheck.check(nested, PG));
        rejected("/*".repeat(10000), PG, ErrorCode.SQL_SYNTAX_INVALID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r", "\r\n", "\t", " ", "\f", "\u000b"})
    void trailingWhitespaceIsExplicitSqlWhitespace(String whitespace) {
        assertDoesNotThrow(() -> LexicalPrecheck.check("SELECT 1;" + whitespace + "-- tail", PG));
        assertDoesNotThrow(() -> LexicalPrecheck.check("SELECT 1;--" + whitespace, MYSQL));
    }

    @Test
    void rejectionPriorityIsBoundedAndDeterministic() {
        SqlLexicalPolicy small = SqlLexicalPolicy.postgresql(true, 8);
        // Encoding wins only when encountered before exceeding the budget. Never scan the suffix
        // after overflow just to find a higher-priority encoding/lexical error.
        rejected("a\u0000" + "x".repeat(100), small, ErrorCode.SQL_SYNTAX_INVALID);
        rejected("a".repeat(9) + "\u0000", small, ErrorCode.SQL_BUDGET_EXCEEDED);
        rejected("a".repeat(9) + "\ud800", small, ErrorCode.SQL_BUDGET_EXCEEDED);
        rejected("\\G" + "x".repeat(100), small, ErrorCode.SQL_BUDGET_EXCEEDED);
        rejected(";\u0000", PG, ErrorCode.SQL_SYNTAX_INVALID);
        rejected("SELECT 1; /*+ x */ SELECT 2", PG, ErrorCode.SQL_UNSUPPORTED);
        rejected("SELECT 1; \\G", PG, ErrorCode.SQL_UNSUPPORTED);
        rejected("SELECT 1; /*", PG, ErrorCode.SQL_SYNTAX_INVALID);
        rejected("SELECT 1; SELECT '", PG, ErrorCode.SQL_NOT_READONLY);
    }

    @Test
    void internalNullPolicyIsNotAnExternalSqlRejection() {
        assertThrows(NullPointerException.class, () -> LexicalPrecheck.check("SELECT 1", null));
        assertThrows(NullPointerException.class, () -> LexicalPrecheck.check(null, null));
        rejected(null, PG, ErrorCode.SQL_SYNTAX_INVALID);
    }

    @Test
    void unknownOrdinaryPunctuationIsLeftForTheAstAllowlist() {
        assertDoesNotThrow(() -> LexicalPrecheck.check("SELECT 1 @? 2", PG));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "\n",
                " --c\n",
                "\u000b\n",
                "\n\u000b",
                "\f\n",
                "\n\f",
                "\r\n\r\n",
                " --a\r\n --b\r\n\t\f\u000b"
            })
    void postgresContinuationMatchesReleaseWhitespaceProductions(String gap) {
        assertDoesNotThrow(() -> LexicalPrecheck.check("SELECT E'a'" + gap + "'b\\';data'", PG));
        rejected("SELECT E'a'" + gap + "'b\\';SELECT '2'", PG, ErrorCode.SQL_SYNTAX_INVALID);
    }

    private static void rejected(String sql, SqlLexicalPolicy policy, ErrorCode code) {
        DomainException error =
                assertThrows(DomainException.class, () -> LexicalPrecheck.check(sql, policy));
        assertEquals(code, error.errorCode());
        assertEquals(code.name(), error.getMessage());
        assertEquals(null, error.getCause());
    }
}
