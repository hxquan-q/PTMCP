package local.ptmcp.admin.api;

import jakarta.servlet.http.HttpServletRequest;

import local.ptmcp.admin.identity.IdentityPasswordService;
import local.ptmcp.admin.identity.IdentityPasswordService.Status;
import local.ptmcp.admin.web.StrictJson;
import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.org.IdempotencyRecorder;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Administrator-only cross-store password change and durable recovery; see ADR-0011. */
@RestController
@ConditionalOnProperty("ptmcp.admin.identity-datasource.url")
@RequestMapping("/api/v1/admin/users")
public class IdentityPasswordController {
    private final IdentityPasswordService service;
    private final IdempotencyRecorder idempotency;

    public IdentityPasswordController(
            IdentityPasswordService service, IdempotencyRecorder idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    public record PasswordReset(String newPassword, String reason) {
        @Override
        public String toString() {
            return "PasswordReset[redacted]";
        }
    }

    public record RecoveryRequest() {}

    @AdminAction("ptmcp:user:read")
    @GetMapping("/{id}/identity")
    public ResponseEntity<Status> status(@PathVariable long id, HttpServletRequest request) {
        ManagementScope.requirePlatformAdminPrincipal(request);
        Status state = service.status(id);
        return ResponseEntity.ok().eTag(IfMatch.etag(state.version())).body(state);
    }

    @AdminAction("ptmcp:user:reset-password")
    @PutMapping("/{id}/password")
    public ResponseEntity<byte[]> reset(
            @PathVariable long id,
            @RequestBody(required = false) byte[] body,
            HttpServletRequest request) {
        long version = IfMatch.requireVersion(request);
        AdminPrincipal principal = ManagementScope.requirePlatformAdminPrincipal(request);
        PasswordReset payload = StrictJson.parse(body, PasswordReset.class);
        return IdempotentWrites.execute(
                idempotency,
                principal.userId(),
                "users.password:" + id,
                idempotencyKey(request),
                version,
                body,
                () ->
                        completed(
                                id,
                                service.reset(
                                        id,
                                        version,
                                        payload.newPassword(),
                                        payload.reason(),
                                        principal.userId())));
    }

    @AdminAction("ptmcp:user:reset-password")
    @PostMapping("/{id}/password/retry")
    public ResponseEntity<byte[]> retry(
            @PathVariable long id,
            @RequestBody(required = false) byte[] body,
            HttpServletRequest request) {
        long version = IfMatch.requireVersion(request);
        AdminPrincipal principal = ManagementScope.requirePlatformAdminPrincipal(request);
        StrictJson.parse(body, RecoveryRequest.class);
        return IdempotentWrites.execute(
                idempotency,
                principal.userId(),
                "users.password.retry:" + id,
                idempotencyKey(request),
                version,
                body,
                () -> completed(id, service.retry(id, version)));
    }

    private static ResponseEntity<Status> completed(long id, long version) {
        return ResponseEntity.ok()
                .eTag(IfMatch.etag(version))
                .body(new Status(id, version, "READY", false));
    }

    private static String idempotencyKey(HttpServletRequest request) {
        String key = request.getHeader("Idempotency-Key");
        if (key == null || key.isBlank()) throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        return key.trim();
    }
}
