package local.ptmcp.persistence.audit.masking;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.util.Arrays;

/**
 * The outcome of running one raw SQL statement through {@link AuditSqlMasker}.
 *
 * <p>When masking succeeded, {@code sqlMasked} carries the comment-free text with every literal
 * replaced by a placeholder and {@code degradedCode}/{@code degradedRuleId} are null. Whenever the
 * masker cannot prove that no literal survived — parse failure, an unhandled literal form, an
 * oversized or NUL-bearing input — {@code sqlMasked} is null and only the SHA-256 fingerprint and
 * the UTF-8 length of the raw input are reported. {@link #toString()} deliberately exposes nothing
 * that could carry raw SQL, so degraded results can never leak through logs or debuggers.
 */
public record MaskedSql(
        String sqlMasked,
        byte[] fingerprint,
        int rawUtf8Length,
        String degradedCode,
        String degradedRuleId) {

    public MaskedSql {
        if (fingerprint == null || fingerprint.length != 32) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (rawUtf8Length < 0) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if ((sqlMasked == null) != (degradedCode != null)
                || (degradedCode != null) != (degradedRuleId != null)) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (degradedRuleId != null && !degradedRuleId.matches("AUDIT-MASK-\\d{3}")) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (sqlMasked != null && sqlMasked.indexOf('\0') >= 0) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        fingerprint = fingerprint.clone();
    }

    public static MaskedSql masked(String sqlMasked, byte[] fingerprint, int rawUtf8Length) {
        return new MaskedSql(sqlMasked, fingerprint, rawUtf8Length, null, null);
    }

    public static MaskedSql degraded(
            byte[] fingerprint, int rawUtf8Length, String degradedCode, String degradedRuleId) {
        return new MaskedSql(null, fingerprint, rawUtf8Length, degradedCode, degradedRuleId);
    }

    public boolean degraded() {
        return sqlMasked == null;
    }

    @Override
    public byte[] fingerprint() {
        return fingerprint.clone();
    }

    @Override
    public String toString() {
        return "MaskedSql[degraded="
                + degraded()
                + ", rawUtf8Length="
                + rawUtf8Length
                + ", degradedCode="
                + degradedCode
                + ", degradedRuleId="
                + degradedRuleId
                + "]";
    }

    @Override
    public boolean equals(Object other) {
        // Records with array components would compare identity; degraded results must never be
        // compared by content anywhere either — the raw fingerprint stays opaque.
        throw new UnsupportedOperationException("MaskedSql is not a value object");
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(fingerprint);
    }
}
