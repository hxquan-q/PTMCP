package local.ptmcp.admin.api;

import jakarta.servlet.http.HttpServletRequest;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.audit.AuditAdminStore;
import local.ptmcp.persistence.audit.masking.SqlFieldCipher;
import local.ptmcp.persistence.audit.wal.WalKey;
import local.ptmcp.persistence.org.DelegationStore;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.LongFunction;

/**
 * /api/v1/admin/audit: list, detail and controlled decrypt. The three permissions are separate —
 * list never returns ciphertext, detail shows only the masked SQL, and decrypt requires an explicit
 * delegation with "audit:decrypt" over the audit record's datasource plus a reason of 10..500
 * characters. The sensitive-access audit row is persisted (ALLOW or DENY) before any plaintext is
 * returned; the response carries no-store and the plaintext never enters sessions, logs or caches.
 * Retention-expired records read as 404.
 */
@RestController
@ConditionalOnProperty("ptmcp.admin.datasource.url")
@RequestMapping("/api/v1/admin/audit")
public class AuditAdminController {
    private final AuditAdminStore store;
    private final DelegationStore delegations;
    private final LongFunction<WalKey> keyResolver;
    private final String deploymentId;

    public AuditAdminController(
            AuditAdminStore store,
            DelegationStore delegations,
            LongFunction<WalKey> keyResolver,
            @org.springframework.beans.factory.annotation.Value(
                            "${ptmcp.admin.audit.deployment-id:}")
                    String deploymentId) {
        this.store = store;
        this.delegations = delegations;
        this.keyResolver = keyResolver;
        this.deploymentId = deploymentId;
    }

    public record AuditSummaryRead(
            String requestId,
            Long userId,
            Long platformId,
            Long datasourceId,
            String tool,
            String startedAt,
            String completedAt,
            String result,
            String verdict,
            String errorCode) {}

    public record AuditDetailRead(
            String requestId,
            Long userId,
            Long platformId,
            Long datasourceId,
            String tool,
            String startedAt,
            String completedAt,
            String result,
            String verdict,
            String errorCode,
            String sqlMasked,
            Integer rawLength,
            Long durationMs,
            Integer queueMs,
            Integer rowsReturned,
            boolean truncated) {}

    public record DecryptRequest(List<String> fields, String reason) {}

    public record DecryptResponse(String sqlRaw, String sqlFinal, String expiresAt) {}

    @GetMapping
    public List<AuditSummaryRead> list(
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long platformId,
            @RequestParam(required = false) Long datasourceId,
            @RequestParam(required = false) String tool,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String verdict,
            @RequestParam(required = false) String errorCode,
            @RequestParam(required = false) String cursorStartedAt,
            @RequestParam(required = false) String cursorRequestId,
            @RequestParam(defaultValue = "100") int pageSize,
            HttpServletRequest request) {
        AuditGate.requireAllowed(request, delegations, AuditGate.ACTION_LIST, datasourceId);
        Instant fromAt = Instant.parse(from);
        Instant toAt = Instant.parse(to);
        AuditAdminStore.Cursor cursor =
                cursorStartedAt != null && cursorRequestId != null
                        ? new AuditAdminStore.Cursor(
                                Instant.parse(cursorStartedAt), UUID.fromString(cursorRequestId))
                        : null;
        List<AuditAdminStore.AuditSummaryRow> rows =
                store.list(
                        fromAt,
                        toAt,
                        userId,
                        platformId,
                        datasourceId,
                        tool,
                        result,
                        verdict,
                        errorCode,
                        cursor,
                        pageSize);
        List<AuditSummaryRead> reads = new ArrayList<>();
        for (AuditAdminStore.AuditSummaryRow row : rows) {
            reads.add(
                    new AuditSummaryRead(
                            row.requestId().toString(),
                            row.userId(),
                            row.platformId(),
                            row.datasourceId(),
                            row.tool(),
                            row.startedAt().toString(),
                            row.completedAt() == null ? null : row.completedAt().toString(),
                            row.result(),
                            row.verdict(),
                            row.errorCode()));
        }
        return reads;
    }

    @GetMapping("/{requestId}")
    public AuditDetailRead detail(@PathVariable String requestId, HttpServletRequest request) {
        UUID id = UUID.fromString(requestId);
        AuditAdminStore.AuditDetailRow row = store.detail(id, Instant.now());
        Long datasourceId = row.datasourceId();
        AuditGate.requireAllowed(request, delegations, AuditGate.ACTION_READ, datasourceId);
        return new AuditDetailRead(
                row.requestId().toString(),
                row.userId(),
                row.platformId(),
                row.datasourceId(),
                row.tool(),
                row.startedAt().toString(),
                row.completedAt() == null ? null : row.completedAt().toString(),
                row.result(),
                row.verdict(),
                row.errorCode(),
                row.sqlMasked(),
                row.rawLength(),
                row.durationMs(),
                row.queueMs(),
                row.rowsReturned(),
                row.truncated());
    }

    @PostMapping("/{requestId}/decrypt")
    public ResponseEntity<DecryptResponse> decrypt(
            @PathVariable String requestId,
            @RequestBody DecryptRequest body,
            HttpServletRequest request) {
        UUID id = UUID.fromString(requestId);
        if (body.fields() == null
                || body.fields().isEmpty()
                || body.reason() == null
                || body.reason().length() < 10
                || body.reason().length() > 500) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        for (String field : body.fields()) {
            if (!"sqlRaw".equals(field) && !"sqlFinal".equals(field)) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
        }
        AdminPrincipal principal = AdminPrincipal.require(request);
        long operatorId = principal.userId();

        // Load the record (retention-expired → 404, not found → 404).
        AuditAdminStore.AuditDetailRow row = store.loadForDecrypt(id, Instant.now());

        // Gate: delegation with audit:decrypt over the record's datasource.
        boolean allowed =
                AuditGate.isAllowed(
                        principal, delegations, AuditGate.ACTION_DECRYPT, row.datasourceId());

        // Persist the access decision BEFORE returning any plaintext.
        store.recordSensitiveAccess(
                id, operatorId, body.reason(), body.fields(), allowed ? "ALLOW" : "DENY");

        if (!allowed) {
            throw new DomainException(ErrorCode.MANAGEMENT_SCOPE_DENIED);
        }

        // Decrypt the requested fields.
        WalKey key = keyResolver.apply(0L);
        String sqlRaw = decryptField(deploymentId, row.sqlRawCipher(), key, id, "sqlRaw");
        String sqlFinal = decryptField(deploymentId, row.sqlFinalCipher(), key, id, "sqlFinal");

        DecryptResponse response =
                new DecryptResponse(sqlRaw, sqlFinal, Instant.now().plusSeconds(300).toString());
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("Pragma", "no-cache")
                .contentType(MediaType.APPLICATION_JSON)
                .body(response);
    }

    private String decryptField(
            String deploymentId, byte[] cipher, WalKey key, UUID requestId, String field) {
        if (cipher == null) {
            return null;
        }
        var policy =
                new local.ptmcp.persistence.audit.masking.SqlFieldCipherPolicy(true, deploymentId);
        byte[] plain = SqlFieldCipher.decryptForAudit(policy, key, requestId, field, cipher);
        return plain == null ? null : new String(plain, java.nio.charset.StandardCharsets.UTF_8);
    }
}
