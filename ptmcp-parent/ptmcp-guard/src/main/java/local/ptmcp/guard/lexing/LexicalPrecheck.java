package local.ptmcp.guard.lexing;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.common.resource.DatabaseEngine;

import java.util.Objects;

/**
 * Input-budget and lexical-completeness filter only, NOT an SQL parser or readonly/security
 * approval. Passing (including for UPDATE/DELETE) MUST proceed through the complete guard pipeline;
 * it grants no authority to borrow a connection or execute SQL. No approved-query object is
 * produced here.
 */
public final class LexicalPrecheck {
    private LexicalPrecheck() {}

    /**
     * Internal null policy is a configuration/programming error (NPE); external null SQL is
     * SQL_SYNTAX_INVALID. Encoding/budget is checked before lexical states: the first invalid code
     * point or budget overflow wins, and an overflow never scans the remaining suffix. Within the
     * bounded lexical pass the first encountered rejection wins. After a final semicolon, special
     * comments/client commands retain SQL_UNSUPPORTED, incomplete ordinary comments retain
     * SQL_SYNTAX_INVALID, and other tokens yield SQL_NOT_READONLY. Unknown ordinary punctuation
     * remains subject to the later parser/AST allowlist, not approval here.
     */
    public static void check(String sql, SqlLexicalPolicy policy) {
        Objects.requireNonNull(policy, "policy");
        checkEncodingAndBudget(sql, policy.maxSqlBytes());
        new Scanner(sql, policy).scan();
    }

    // Count without encoding/copying the input. At most the tightened budget plus one code point is
    // visited, even if the caller already holds an enormous String. Bad UTF-16 is never replaced.
    private static void checkEncodingAndBudget(String sql, int maxBytes) {
        if (sql == null) {
            throw rejection(ErrorCode.SQL_SYNTAX_INVALID);
        }
        int bytes = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == 0 || Character.isLowSurrogate(c)) {
                throw rejection(ErrorCode.SQL_SYNTAX_INVALID);
            }
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= sql.length() || !Character.isLowSurrogate(sql.charAt(++i))) {
                    throw rejection(ErrorCode.SQL_SYNTAX_INVALID);
                }
                bytes += 4;
            } else {
                bytes += c < 0x80 ? 1 : c < 0x800 ? 2 : 3;
            }
            if (bytes > maxBytes) {
                throw rejection(ErrorCode.SQL_BUDGET_EXCEEDED);
            }
        }
    }

    private static DomainException rejection(ErrorCode code) {
        return new DomainException(code);
    }

    private static boolean whitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == '\u000b';
    }

    private static boolean identifierStart(char c) {
        return c == '_' || c >= 128 || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean identifierPart(char c) {
        return identifierStart(c) || (c >= '0' && c <= '9') || c == '$';
    }

    private static final class Scanner {
        private final String sql;
        private final SqlLexicalPolicy policy;
        private final boolean postgres;
        private int pos;
        private boolean terminated;
        private boolean seenToken;
        private boolean previousPgString;
        private boolean previousPgEscapes;
        private boolean gapNewline;

        private Scanner(String sql, SqlLexicalPolicy policy) {
            this.sql = sql;
            this.policy = policy;
            this.postgres = policy.engine() == DatabaseEngine.POSTGRESQL;
        }

        private void scan() {
            while (pos < sql.length()) {
                char c = sql.charAt(pos);
                if (whitespace(c)) {
                    gapNewline |= c == '\n' || c == '\r';
                    pos++;
                    continue;
                }
                if (lineComment()) {
                    skipLine();
                    continue;
                }
                if (at("/*")) {
                    skipBlock();
                    // PG REL_18_6 scan.l quotecontinue permits whitespace/-- comments, NOT blocks.
                    // Both non_newline_space and special_whitespace include VT/FF (before/after
                    // the first CR/LF); a -- comment consumes up to, but not including, CR/LF.
                    previousPgString = false;
                    continue;
                }
                if (c == '\\') {
                    // Neither dialect has a bare backslash SQL token. Reject all client commands,
                    // not merely the MySQL \G example, without interpreting client-side syntax.
                    throw rejection(ErrorCode.SQL_UNSUPPORTED);
                }
                if (terminated) {
                    throw rejection(ErrorCode.SQL_NOT_READONLY);
                }
                if (c == ';') {
                    if (!seenToken) {
                        throw rejection(ErrorCode.SQL_NOT_READONLY);
                    }
                    terminated = true;
                    pos++;
                    continue;
                }
                seenToken = true;
                if (c == '\'') {
                    boolean escapes =
                            postgres
                                    ? previousPgString && gapNewline
                                            ? previousPgEscapes
                                            : !policy.standardConformingStrings()
                                    : !policy.noBackslashEscapes();
                    quoted('\'', escapes);
                    previousPgString = postgres;
                    previousPgEscapes = escapes;
                    gapNewline = false;
                    continue;
                }
                previousPgString = false;
                gapNewline = false;
                if (c == '"') {
                    quoted('"', !postgres && !policy.ansiQuotes() && !policy.noBackslashEscapes());
                } else if (c == '`') {
                    if (postgres) {
                        throw rejection(ErrorCode.SQL_UNSUPPORTED);
                    }
                    quoted('`', false);
                } else if (postgres && c == '$' && dollarQuote()) {
                    // dollarQuote advances over one complete, uninterpreted literal.
                } else if (identifierStart(c)) {
                    identifier();
                } else {
                    pos++;
                }
            }
            if (!seenToken) {
                throw rejection(ErrorCode.SQL_SYNTAX_INVALID);
            }
        }

        private boolean lineComment() {
            return (!postgres && sql.charAt(pos) == '#')
                    || (at("--")
                            && (postgres
                                    || pos + 2 == sql.length()
                                    || sql.charAt(pos + 2) <= ' '
                                    || sql.charAt(pos + 2) == '\u007f'));
        }

        private void skipLine() {
            while (pos < sql.length() && sql.charAt(pos) != '\n' && sql.charAt(pos) != '\r') {
                pos++;
            }
        }

        private void skipBlock() {
            int depth = 1;
            rejectSpecialComment();
            pos += 2;
            while (pos < sql.length()) {
                if (at("/*")) {
                    rejectSpecialComment();
                    if (postgres) {
                        depth++;
                        pos += 2;
                    } else {
                        // MySQL does not nest: the star in an inner /* may itself start the
                        // closing */ (/* /*/). Do not skip that possible terminator.
                        pos++;
                    }
                } else if (at("*/")) {
                    pos += 2;
                    if (--depth == 0) {
                        return;
                    }
                } else {
                    pos++;
                }
            }
            throw rejection(ErrorCode.SQL_SYNTAX_INVALID);
        }

        private void rejectSpecialComment() {
            if (pos + 2 < sql.length()
                    && (sql.charAt(pos + 2) == '!' || sql.charAt(pos + 2) == '+')) {
                throw rejection(ErrorCode.SQL_UNSUPPORTED);
            }
        }

        private void quoted(char quote, boolean escapes) {
            pos++;
            while (pos < sql.length()) {
                char c = sql.charAt(pos++);
                if (escapes && c == '\\') {
                    if (pos == sql.length()) {
                        throw rejection(ErrorCode.SQL_SYNTAX_INVALID);
                    }
                    pos++;
                } else if (c == quote) {
                    if (pos < sql.length() && sql.charAt(pos) == quote) {
                        pos++;
                    } else {
                        return;
                    }
                }
            }
            throw rejection(ErrorCode.SQL_SYNTAX_INVALID);
        }

        private void identifier() {
            int start = pos++;
            while (pos < sql.length() && identifierPart(sql.charAt(pos))) {
                pos++;
            }
            // Prefixes are recognized only as standalone tokens, not inside fooE or foo$tag$.
            if (postgres && pos == start + 1 && pos < sql.length() && sql.charAt(pos) == '\'') {
                char prefix = sql.charAt(start);
                if (prefix == 'e'
                        || prefix == 'E'
                        || prefix == 'b'
                        || prefix == 'B'
                        || prefix == 'x'
                        || prefix == 'X') {
                    boolean escapes = prefix == 'e' || prefix == 'E';
                    quoted('\'', escapes);
                    previousPgString = true;
                    previousPgEscapes = escapes;
                }
            }
        }

        private boolean dollarQuote() {
            int end = pos + 1;
            if (end < sql.length() && identifierStart(sql.charAt(end))) {
                end++;
                while (end < sql.length()
                        && sql.charAt(end) != '$'
                        && identifierPart(sql.charAt(end))) {
                    end++;
                }
            }
            if (end >= sql.length() || sql.charAt(end) != '$') {
                return false;
            }
            // This bounded copy is made only AFTER the UTF-8 budget. Matching attempts start at $
            // and delimiter interiors contain no $, so failed prefixes do not rescan each other.
            String delimiter = sql.substring(pos, end + 1);
            pos = end + 1;
            while (pos < sql.length()) {
                if (sql.charAt(pos) == '$' && sql.startsWith(delimiter, pos)) {
                    pos += delimiter.length();
                    return true;
                }
                pos++;
            }
            throw rejection(ErrorCode.SQL_SYNTAX_INVALID);
        }

        private boolean at(String token) {
            return sql.startsWith(token, pos);
        }
    }
}
