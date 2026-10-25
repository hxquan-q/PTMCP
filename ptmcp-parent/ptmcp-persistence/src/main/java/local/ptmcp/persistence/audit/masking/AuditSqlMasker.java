package local.ptmcp.persistence.audit.masking;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * The controlled SQL masking entry point for the audit domain (audit.md §4).
 *
 * <p>Design: the raw statement is first parsed with the same parser family the SQL guard uses
 * (JSqlParser), so a successful parse is the structural oracle — comments never survive it and the
 * text is known to be one statement. The masked projection is then produced by a controlled token
 * transform over the raw text: every string, number, hex/bit, and date/time literal becomes a
 * {@code ?} placeholder everywhere (SELECT projections, CASE, CAST, IN lists — not just WHERE),
 * comments are dropped and whitespace is canonicalized. The masked text must re-parse and must
 * contain no surviving literal token; any doubt degrades the whole result to hash-only instead of
 * falling back to a partially masked plaintext. That keeps the "cannot prove correct masking"
 * invariant without pretending to enumerate every AST node class.
 *
 * <p>Budgets: an input above {@link #MAX_MASKING_INPUT_BYTES} UTF-8 bytes or containing a NUL byte
 * is never masked — only its fingerprint and UTF-8 length are recorded, and this class never asks
 * for more input than the caller already holds. Parameterized statements belong to the guard
 * allowlist layer; this class masks whatever literals reach the audit path.
 */
public final class AuditSqlMasker {
    public static final int MAX_MASKING_INPUT_BYTES = 64 * 1024;
    public static final String RULE_PARSE_ERROR = "AUDIT-MASK-001";
    public static final String RULE_LITERAL_REMAINS = "AUDIT-MASK-002";
    public static final String RULE_TOO_LARGE = "AUDIT-MASK-003";
    public static final String RULE_NUL_BYTE = "AUDIT-MASK-004";
    public static final String RULE_MASKED_REPARSE = "AUDIT-MASK-005";

    private static final String FINGERPRINT_SCHEMA = "ptmcp-audit-sql-fp/v1";
    private static final char PLACEHOLDER = '?';

    private final String dialect;

    /**
     * The dialect label is part of the fingerprint, so the same text hashes differently per
     * dialect.
     */
    public AuditSqlMasker(String dialect) {
        if (dialect == null
                || dialect.isBlank()
                || dialect.length() > 32
                || !dialect.matches("[a-z0-9_-]+")) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        this.dialect = dialect;
    }

    /**
     * Masks one statement. {@code resourceIdentity} (datasource/resource coordinates, may be null)
     * is folded into the fingerprint so identical texts in different resources do not collide.
     */
    public MaskedSql mask(String resourceIdentity, String rawSql) {
        if (rawSql == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        byte[] raw = rawSql.getBytes(StandardCharsets.UTF_8);
        for (byte value : raw) {
            if (value == 0) {
                return degraded(raw, RULE_NUL_BYTE);
            }
        }
        if (raw.length > MAX_MASKING_INPUT_BYTES) {
            return degraded(raw, RULE_TOO_LARGE);
        }
        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(rawSql);
        } catch (Exception failure) {
            return degraded(raw, RULE_PARSE_ERROR);
        }
        if (statement == null) {
            return degraded(raw, RULE_PARSE_ERROR);
        }
        String masked = transform(rawSql);
        try {
            if (CCJSqlParserUtil.parse(masked) == null) {
                return degraded(raw, RULE_LITERAL_REMAINS);
            }
        } catch (Exception failure) {
            return degraded(raw, RULE_MASKED_REPARSE);
        }
        if (containsSurvivingLiteral(masked)) {
            return degraded(raw, RULE_LITERAL_REMAINS);
        }
        return MaskedSql.masked(masked, fingerprint(resourceIdentity, masked), raw.length);
    }

    /**
     * Single-pass canonical transform: comments dropped, literal tokens replaced, whitespace
     * collapsed. The scanner is intentionally conservative — anything it cannot classify is emitted
     * verbatim and the surviving-literal scan decides whether that is safe.
     */
    private String transform(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int index = 0;
        int length = sql.length();
        while (index < length) {
            char current = sql.charAt(index);
            if (Character.isWhitespace(current)) {
                out.append(' ');
                index = skipWhitespace(sql, index);
                continue;
            }
            if (current == '-' && index + 1 < length && sql.charAt(index + 1) == '-') {
                index = endOfLineComment(sql, index);
                continue;
            }
            if (current == '#') {
                index = endOfLineComment(sql, index);
                continue;
            }
            if (current == '/' && index + 1 < length && sql.charAt(index + 1) == '*') {
                index = endOfBlockComment(sql, index);
                continue;
            }
            if (current == '\'') {
                index = endOfStringLiteral(sql, index);
                out.append('\'').append(PLACEHOLDER).append('\'');
                continue;
            }
            if ((current == 'x' || current == 'X')
                    && index + 1 < length
                    && sql.charAt(index + 1) == '\'') {
                index = endOfStringLiteral(sql, index + 1);
                out.append(PLACEHOLDER);
                continue;
            }
            if ((current == 'b' || current == 'B')
                    && index + 1 < length
                    && sql.charAt(index + 1) == '\'') {
                index = endOfStringLiteral(sql, index + 1);
                out.append(PLACEHOLDER);
                continue;
            }
            if (current == '0'
                    && index + 1 < length
                    && (sql.charAt(index + 1) == 'x'
                            || sql.charAt(index + 1) == 'X'
                            || sql.charAt(index + 1) == 'b'
                            || sql.charAt(index + 1) == 'B')) {
                index = skipHexOrBit(sql, index);
                out.append(PLACEHOLDER);
                continue;
            }
            if ((isDigit(current)
                            || (current == '.'
                                    && index + 1 < length
                                    && isDigit(sql.charAt(index + 1))))
                    && !insideIdentifier(out)) {
                index = skipNumber(sql, index);
                out.append(PLACEHOLDER);
                continue;
            }
            int dateTimeEnd = endOfDateTimeLiteral(sql, index);
            if (dateTimeEnd > index) {
                int keywordEnd = index;
                while (keywordEnd < sql.length()
                        && !Character.isWhitespace(sql.charAt(keywordEnd))) {
                    keywordEnd++;
                }
                out.append(sql, index, keywordEnd).append(' ');
                out.append('\'').append(PLACEHOLDER).append('\'');
                index = dateTimeEnd;
                continue;
            }
            out.append(current);
            index++;
        }
        return out.toString().trim().replaceAll(" +", " ");
    }

    private static int skipWhitespace(String sql, int index) {
        while (index < sql.length() && Character.isWhitespace(sql.charAt(index))) {
            index++;
        }
        return index;
    }

    private static int endOfLineComment(String sql, int index) {
        while (index < sql.length() && sql.charAt(index) != '\n' && sql.charAt(index) != '\r') {
            index++;
        }
        return index;
    }

    private static int endOfBlockComment(String sql, int index) {
        index += 2;
        while (index + 1 < sql.length()
                && !(sql.charAt(index) == '*' && sql.charAt(index + 1) == '/')) {
            index++;
        }
        return Math.min(index + 2, sql.length());
    }

    /** Leaves the index just past the closing quote of a single-quoted literal. */
    private static int endOfStringLiteral(String sql, int index) {
        index++;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\\' && index + 1 < sql.length()) {
                index += 2;
                continue;
            }
            if (current == '\'') {
                if (index + 1 < sql.length() && sql.charAt(index + 1) == '\'') {
                    index += 2;
                    continue;
                }
                return index + 1;
            }
            index++;
        }
        return sql.length();
    }

    private static int skipHexOrBit(String sql, int index) {
        index += 2;
        if (index < sql.length() && sql.charAt(index) == '\'') {
            return endOfStringLiteral(sql, index);
        }
        while (index < sql.length() && Character.digit(sql.charAt(index), 16) >= 0) {
            index++;
        }
        return index;
    }

    private static int skipNumber(String sql, int index) {
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (isDigit(current) || current == '.' || current == 'e' || current == 'E') {
                index++;
                continue;
            }
            if ((current == '-' || current == '+')
                    && index > 0
                    && (sql.charAt(index - 1) == 'e' || sql.charAt(index - 1) == 'E')
                    && index + 1 < sql.length()
                    && isDigit(sql.charAt(index + 1))) {
                index++;
                continue;
            }
            break;
        }
        return index;
    }

    /**
     * MySQL allows {@code DATE ' literal} prefixes. When the token at {@code index} is such a
     * keyword immediately followed by a string literal, this returns the index past the closing
     * quote; otherwise {@code index}.
     */
    private static int endOfDateTimeLiteral(String sql, int index) {
        for (String keyword : new String[] {"date ", "time ", "timestamp "}) {
            if (sql.length() - index >= keyword.length()
                    && sql.substring(index, index + keyword.length()).equalsIgnoreCase(keyword)) {
                int quote = index + keyword.length();
                if (quote < sql.length() && sql.charAt(quote) == '\'') {
                    return endOfStringLiteral(sql, quote);
                }
                return index;
            }
        }
        return index;
    }

    /**
     * The safety net: a masked statement must not contain any literal token. Placeholders ({@code
     * ?} and {@code '?'}) are the only acceptable quote/number forms.
     */
    private static boolean containsSurvivingLiteral(String masked) {
        int index = 0;
        int length = masked.length();
        while (index < length) {
            char current = masked.charAt(index);
            if (current == '\'') {
                int end = endOfStringLiteral(masked, index);
                String literal = masked.substring(index + 1, Math.min(end, length) - 1);
                if (!literal.equals(String.valueOf(PLACEHOLDER))) {
                    return true;
                }
                index = end;
                continue;
            }
            if ((isDigit(current)
                            || (current == '.'
                                    && index + 1 < length
                                    && isDigit(masked.charAt(index + 1))))
                    && (index == 0 || !isIdentifierPart(masked.charAt(index - 1)))) {
                return true;
            }
            if ((current == 'x' || current == 'X' || current == 'b' || current == 'B')
                    && index + 1 < length
                    && masked.charAt(index + 1) == '\'') {
                return true;
            }
            if (current == '0'
                    && index + 1 < length
                    && (masked.charAt(index + 1) == 'x'
                            || masked.charAt(index + 1) == 'X'
                            || masked.charAt(index + 1) == 'b'
                            || masked.charAt(index + 1) == 'B')) {
                return true;
            }
            index++;
        }
        return false;
    }

    private static boolean isDigit(char value) {
        return value >= '0' && value <= '9';
    }

    private static boolean isIdentifierPart(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || value == '$';
    }

    /** True when the transformed output so far ends inside an identifier token. */
    private static boolean insideIdentifier(StringBuilder out) {
        if (out.length() == 0) {
            return false;
        }
        char last = out.charAt(out.length() - 1);
        return isIdentifierPart(last) && last != PLACEHOLDER;
    }

    private MaskedSql degraded(byte[] raw, String ruleId) {
        String code =
                switch (ruleId) {
                    case RULE_PARSE_ERROR -> "PARSE_ERROR";
                    case RULE_LITERAL_REMAINS, RULE_MASKED_REPARSE -> "MASKING_UNPROVEN";
                    case RULE_TOO_LARGE -> "SQL_TOO_LARGE";
                    case RULE_NUL_BYTE -> "NUL_BYTE";
                    default -> "MASKING_UNPROVEN";
                };
        return MaskedSql.degraded(Hashes.sha256(raw), raw.length, code, ruleId);
    }

    private byte[] fingerprint(String resourceIdentity, String canonicalSql) {
        String identity =
                resourceIdentity == null || resourceIdentity.isBlank() ? "-" : resourceIdentity;
        String material =
                FINGERPRINT_SCHEMA
                        + "\n"
                        + dialect.toLowerCase(Locale.ROOT)
                        + "\n"
                        + identity
                        + "\n"
                        + canonicalSql;
        return Hashes.sha256(material.getBytes(StandardCharsets.UTF_8));
    }

    /** Local SHA-256 helper; masking never touches key material. */
    private static final class Hashes {
        private Hashes() {}

        static byte[] sha256(byte[] value) {
            try {
                return MessageDigest.getInstance("SHA-256").digest(value);
            } catch (NoSuchAlgorithmException failure) {
                throw new IllegalStateException("SHA-256 is unavailable", failure);
            }
        }
    }
}
