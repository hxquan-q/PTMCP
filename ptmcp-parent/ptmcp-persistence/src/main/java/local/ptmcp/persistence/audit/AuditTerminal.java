package local.ptmcp.persistence.audit;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.time.Instant;
import java.util.UUID;

/**
 * One durable outcome for a request. The record repeats the call identity so that a terminal event
 * arriving before its START can create the audit row on its own; a late START only fills blanks.
 *
 * <p>{@code RUNNING} is part of the persisted result domain but cannot be terminal: accepting it
 * would consume the single terminal event while leaving the outcome unknown forever.
 */
public record AuditTerminal(
        UUID requestId,
        Result result,
        Verdict verdict,
        String errorCode,
        Long durationMs,
        Integer queueMs,
        Integer rowsReturned,
        boolean truncated,
        String maskedSql,
        byte[] sqlFingerprint,
        byte[] encryptedRaw,
        String keyId,
        String tool,
        Long userId,
        Long platformId,
        Long datasourceId,
        Instant startedAt,
        byte[] encryptedFinal) {
    /** Callers from before the final-SQL ciphertext existed; the final form stays absent. */
    public AuditTerminal(
            UUID requestId,
            Result result,
            Verdict verdict,
            String errorCode,
            Long durationMs,
            Integer queueMs,
            Integer rowsReturned,
            boolean truncated,
            String maskedSql,
            byte[] sqlFingerprint,
            byte[] encryptedRaw,
            String keyId,
            String tool,
            Long userId,
            Long platformId,
            Long datasourceId,
            Instant startedAt) {
        this(
                requestId,
                result,
                verdict,
                errorCode,
                durationMs,
                queueMs,
                rowsReturned,
                truncated,
                maskedSql,
                sqlFingerprint,
                encryptedRaw,
                keyId,
                tool,
                userId,
                platformId,
                datasourceId,
                startedAt,
                null);
    }

    public static final int SQL_FINGERPRINT_BYTES = 32;
    public static final int MAX_MASKED_SQL_CHARS = 1_048_576;
    public static final int MAX_ENCRYPTED_RAW_BYTES = 1_048_576;

    public AuditTerminal {
        if (requestId == null || startedAt == null || result == null || verdict == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (result == Result.RUNNING) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (tool == null || !AuditStart.SUPPORTED_TOOLS.contains(tool)) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (userId != null && userId <= 0
                || platformId != null && platformId <= 0
                || datasourceId != null && datasourceId <= 0) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (durationMs != null && durationMs < 0
                || queueMs != null && queueMs < 0
                || rowsReturned != null && rowsReturned < 0) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (errorCode != null && !errorCode.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (maskedSql != null && maskedSql.length() > MAX_MASKED_SQL_CHARS) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (sqlFingerprint != null && sqlFingerprint.length != SQL_FINGERPRINT_BYTES) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if ((encryptedRaw == null) != (keyId == null)) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (keyId != null && (keyId.isBlank() || keyId.length() > 128)) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (encryptedRaw != null
                && (encryptedRaw.length == 0 || encryptedRaw.length > MAX_ENCRYPTED_RAW_BYTES)) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (encryptedFinal != null
                && (encryptedFinal.length == 0
                        || encryptedFinal.length > MAX_ENCRYPTED_RAW_BYTES)) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        // The final form is sealed with the same audit key as the raw form: one key id covers both.
        if (encryptedFinal != null && keyId == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        sqlFingerprint = sqlFingerprint == null ? null : sqlFingerprint.clone();
        encryptedRaw = encryptedRaw == null ? null : encryptedRaw.clone();
        encryptedFinal = encryptedFinal == null ? null : encryptedFinal.clone();
    }

    @Override
    public byte[] sqlFingerprint() {
        return sqlFingerprint == null ? null : sqlFingerprint.clone();
    }

    @Override
    public byte[] encryptedRaw() {
        return encryptedRaw == null ? null : encryptedRaw.clone();
    }

    @Override
    public byte[] encryptedFinal() {
        return encryptedFinal == null ? null : encryptedFinal.clone();
    }

    public enum Result {
        RUNNING,
        SUCCESS,
        ERROR,
        UNKNOWN
    }

    public enum Verdict {
        ALLOW,
        DENY,
        ERROR,
        UNKNOWN
    }
}
