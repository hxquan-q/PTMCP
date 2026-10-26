package local.ptmcp.admin.api;

import jakarta.servlet.http.HttpServletRequest;

import local.ptmcp.admin.audit.AuditQueryService;
import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * /api/v1/admin/audit. Platform-admin only. The projection is read-only and masked: raw SQL ciphers
 * and key ids stay in storage, so this console can never become a plaintext oracle.
 */
@RestController
@ConditionalOnProperty("ptmcp.admin.datasource.url")
@RequestMapping("/api/v1/admin/audit")
public class AuditAdminController {
    private final AuditQueryService audits;

    public AuditAdminController(AuditQueryService audits) {
        this.audits = audits;
    }

    public record AdminAuditPageView(
            List<AuditQueryService.AdminAuditSummary> items, String nextCursor) {}

    @GetMapping
    public ResponseEntity<AdminAuditPageView> list(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String platformId,
            @RequestParam(required = false) String datasourceId,
            @RequestParam(required = false) String tool,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String verdict,
            @RequestParam(required = false) String errorCode,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer pageSize,
            HttpServletRequest request) {
        ManagementScope.requirePlatformAdmin(request);
        AuditQueryService.AdminAuditPage page =
                audits.adminHistory(
                        instantOrNull(from),
                        instantOrNull(to),
                        idOrNull(userId),
                        idOrNull(platformId),
                        idOrNull(datasourceId),
                        tool,
                        result,
                        verdict,
                        errorCode,
                        cursor,
                        pageSize);
        return ResponseEntity.ok(new AdminAuditPageView(page.items(), page.nextCursor()));
    }

    @GetMapping("/{requestId}")
    public AuditQueryService.AdminAuditDetail detail(
            @PathVariable String requestId, HttpServletRequest request) {
        ManagementScope.requirePlatformAdmin(request);
        return audits.adminDetail(requestId);
    }

    private static Instant instantOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return java.time.Instant.parse(value.trim());
        } catch (RuntimeException failure) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
    }

    private static Long idOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            if (parsed <= 0) {
                throw new NumberFormatException("not positive");
            }
            return parsed;
        } catch (NumberFormatException failure) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
    }
}
