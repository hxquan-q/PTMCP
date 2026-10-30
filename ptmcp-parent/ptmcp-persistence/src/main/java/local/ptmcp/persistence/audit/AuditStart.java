package local.ptmcp.persistence.audit;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Minimal admission record for a tool call. It carries no SQL text, token or result data; the
 * caller supplies only a digest and the raw byte length so that a rejected or failed parse cannot
 * leak the statement into durable admission state.
 *
 * <p>The tool name is a closed set. Callers persist this record before expensive guard work and
 * before any business connection is borrowed.
 */
public record AuditStart(
        UUID requestId,
        Long userId,
        Long platformId,
        String tool,
        byte[] sqlHash,
        Integer rawLength,
        Instant startedAt) {
    public static final int SQL_HASH_BYTES = 32;
    public static final int MAX_RAW_LENGTH = 1_048_576;
    public static final Set<String> SUPPORTED_TOOLS =
            Set.of(
                    "list_datasources",
                    "list_databases_and_tables",
                    "get_table_schema",
                    "execute_readonly_sql");

    public AuditStart {
        if (requestId == null || startedAt == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (userId != null && userId <= 0 || platformId != null && platformId <= 0) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (tool == null || !SUPPORTED_TOOLS.contains(tool)) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (sqlHash != null && sqlHash.length != SQL_HASH_BYTES) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if ((sqlHash == null) != (rawLength == null)) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (rawLength != null && (rawLength < 0 || rawLength > MAX_RAW_LENGTH)) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        sqlHash = sqlHash == null ? null : sqlHash.clone();
    }

    @Override
    public byte[] sqlHash() {
        return sqlHash == null ? null : sqlHash.clone();
    }
}
