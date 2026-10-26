package local.ptmcp.admin.api;

import jakarta.servlet.http.HttpServletRequest;

import local.ptmcp.admin.identity.IdentityPasswordService;
import local.ptmcp.admin.web.StrictJson;
import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.org.IdempotencyRecorder;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * PUT /api/v1/admin/users/{id}/password: reason-gated password reset with real credential
 * invalidation (security epoch + refresh-family revocation in the metadata transaction, then the
 * bcrypt write into the host identity store). The plaintext exists only inside one request frame
 * and is never logged. If-Match carries the console aggregate version.
 */
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

    public record PasswordReset(String newPassword, String reason) {}

    @PutMapping("/{id}/password")
    public ResponseEntity<byte[]> reset(
            @PathVariable long id,
            @RequestBody(required = false) byte[] body,
            HttpServletRequest request) {
        long expectedVersion = IfMatch.requireVersion(request);
        AdminPrincipal principal = ManagementScope.requirePlatformAdminPrincipal(request);
        PasswordReset payload = StrictJson.parse(body, PasswordReset.class);
        return IdempotentWrites.execute(
                idempotency,
                principal.userId(),
                "users.password:" + id,
                idempotencyKey(request),
                body,
                () -> {
                    long epoch =
                            service.reset(
                                    id,
                                    expectedVersion,
                                    payload.newPassword(),
                                    payload.reason(),
                                    principal.userId());
                    return ResponseEntity.noContent()
                            .location(URI.create("/api/v1/admin/users/" + id))
                            .header("X-Security-Epoch", Long.toString(epoch))
                            .build();
                });
    }

    private static String idempotencyKey(HttpServletRequest request) {
        String key = request.getHeader("Idempotency-Key");
        if (key == null || key.isBlank()) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        return key.trim();
    }
}
