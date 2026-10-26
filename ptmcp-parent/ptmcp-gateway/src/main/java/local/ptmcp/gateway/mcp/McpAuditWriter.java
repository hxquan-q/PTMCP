package local.ptmcp.gateway.mcp;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.audit.AuditRepository;
import local.ptmcp.persistence.audit.AuditStart;
import local.ptmcp.persistence.audit.AuditTerminal;
import local.ptmcp.persistence.audit.wal.TerminalWal;

import java.util.Objects;

/**
 * Durable audit admission and termination for tool calls. START must commit on the primary metadata
 * database before any guard or business work; a failure surfaces as {@code AUDIT_UNAVAILABLE} and
 * the call is rejected. TERMINAL prefers the primary database; when that write cannot be confirmed
 * the encrypted local WAL with fsync is an acceptable durable ending, and only when both fail does
 * the call end without a durable outcome and fail closed.
 */
final class McpAuditWriter {
    private final AuditRepository repository;
    private final TerminalWal wal;

    McpAuditWriter(AuditRepository repository, TerminalWal wal) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.wal = wal;
    }

    /** Durable admission; throws {@code AUDIT_UNAVAILABLE} when the primary is unwritable. */
    void start(AuditStart start) {
        try {
            repository.insertStart(start);
        } catch (DomainException failure) {
            if (unavailable(failure)) {
                throw new DomainException(ErrorCode.AUDIT_UNAVAILABLE);
            }
            throw failure;
        }
    }

    /** Confirms the START row of this request is durably present before a business checkout. */
    void confirmStart(java.util.UUID requestId) {
        try {
            if (!repository.confirmStart(requestId)) {
                throw new DomainException(ErrorCode.AUDIT_UNAVAILABLE);
            }
        } catch (DomainException failure) {
            if (unavailable(failure)) {
                throw new DomainException(ErrorCode.AUDIT_UNAVAILABLE);
            }
            throw failure;
        }
    }

    /** Durable single ending; DB first, then the fsynced WAL; both failing rejects the call. */
    void terminal(AuditTerminal terminal) {
        try {
            AuditRepository.TerminalState state = repository.submitTerminal(terminal);
            if (state.conflict()) {
                // A different durable ending already exists; never overwrite or mask it.
                throw new DomainException(ErrorCode.INTERNAL_ERROR);
            }
            return;
        } catch (DomainException failure) {
            if (!unavailable(failure)) {
                throw failure;
            }
        }
        if (wal == null) {
            throw new DomainException(ErrorCode.AUDIT_UNAVAILABLE);
        }
        wal.append(terminal);
    }

    private static boolean unavailable(DomainException failure) {
        ErrorCode code = failure.errorCode();
        return code == ErrorCode.DEPENDENCY_UNAVAILABLE
                || code == ErrorCode.OPERATION_UNKNOWN
                || code == ErrorCode.SECURITY_STATE_UNAVAILABLE;
    }
}
