package local.ptmcp.admin.api;

import jakarta.servlet.http.HttpServletRequest;

import local.ptmcp.admin.platform.PlatformAdminService;
import local.ptmcp.admin.platform.PlatformDirectoryService;
import local.ptmcp.admin.web.StrictJson;
import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.org.IdempotencyRecorder;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/**
 * /api/v1/admin/platforms: the directory list plus the platform-console CRUD. Proof configuration
 * is sealed by the deployment cipher and never readable again; mutations are optimistic (If-Match)
 * and idempotent (Idempotency-Key on POST) and each writes one admin_operation_log row. Scope and
 * client management stay in their own slices.
 */
@RestController
@ConditionalOnProperty("ptmcp.admin.datasource.url")
@RequestMapping("/api/v1/admin/platforms")
public class PlatformAdminController {
    private final PlatformDirectoryService platforms;
    private final PlatformAdminService mutations;
    private final IdempotencyRecorder idempotency;

    public PlatformAdminController(
            PlatformDirectoryService platforms,
            PlatformAdminService mutations,
            IdempotencyRecorder idempotency) {
        this.platforms = platforms;
        this.mutations = mutations;
        this.idempotency = idempotency;
    }

    public record PlatformWrite(
            String code,
            String name,
            Integer tier,
            String catalogMode,
            String proofMode,
            String status,
            ProofConfig proofConfig) {}

    public record ProofConfig(String certificateFingerprint, String proxyNetwork, String secret) {}

    public record PageView(
            java.util.List<PlatformDirectoryService.PlatformView> items, String nextCursor) {}

    @GetMapping
    public ResponseEntity<PageView> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer pageSize,
            HttpServletRequest request) {
        ManagementScope.requirePlatformAdmin(request);
        PlatformDirectoryService.Page page =
                platforms.list(keyword, status, parseCursor(cursor), pageSize);
        return ResponseEntity.ok(new PageView(page.items(), page.nextCursor()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PlatformDirectoryService.PlatformView> get(
            @PathVariable long id, HttpServletRequest request) {
        ManagementScope.requirePlatformAdmin(request);
        PlatformDirectoryService.PlatformView view = toView(mutations.get(id));
        return ResponseEntity.ok().eTag(IfMatch.etag(Long.parseLong(view.version()))).body(view);
    }

    @PostMapping
    public ResponseEntity<byte[]> create(
            @RequestBody(required = false) byte[] body, HttpServletRequest request) {
        AdminPrincipal principal = ManagementScope.requirePlatformAdminPrincipal(request);
        PlatformWrite write = StrictJson.parse(body, PlatformWrite.class);
        try {
            return doCreate(principal, write, body, request);
        } catch (RuntimeException error) {
            error.printStackTrace();
            throw error;
        }
    }

    private ResponseEntity<byte[]> doCreate(
            AdminPrincipal principal,
            PlatformWrite write,
            byte[] body,
            HttpServletRequest request) {
        return IdempotentWrites.execute(
                idempotency,
                principal.userId(),
                "platforms.create",
                idempotencyKey(request),
                body,
                () -> {
                    PlatformDirectoryService.PlatformView created =
                            toView(
                                    mutations.create(
                                            toServiceWrite(write),
                                            principal.userId(),
                                            UUID.randomUUID().toString()));
                    return ResponseEntity.created(
                                    URI.create("/api/v1/admin/platforms/" + created.id()))
                            .eTag(IfMatch.etag(Long.parseLong(created.version())))
                            .body(created);
                });
    }

    @PutMapping("/{id}")
    public ResponseEntity<PlatformDirectoryService.PlatformView> update(
            @PathVariable long id,
            @RequestBody(required = false) byte[] body,
            HttpServletRequest request) {
        long expectedVersion = IfMatch.requireVersion(request);
        AdminPrincipal principal = ManagementScope.requirePlatformAdminPrincipal(request);
        PlatformWrite write = StrictJson.parse(body, PlatformWrite.class);
        PlatformDirectoryService.PlatformView updated =
                toView(
                        mutations.update(
                                id,
                                expectedVersion,
                                toServiceWrite(write),
                                principal.userId(),
                                UUID.randomUUID().toString()));
        return ResponseEntity.ok()
                .eTag(IfMatch.etag(Long.parseLong(updated.version())))
                .body(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<PlatformDirectoryService.PlatformView> delete(
            @PathVariable long id, HttpServletRequest request) {
        long expectedVersion = IfMatch.requireVersion(request);
        AdminPrincipal principal = ManagementScope.requirePlatformAdminPrincipal(request);
        PlatformDirectoryService.PlatformView deleted =
                toView(
                        mutations.delete(
                                id,
                                expectedVersion,
                                principal.userId(),
                                UUID.randomUUID().toString()));
        return ResponseEntity.ok()
                .eTag(IfMatch.etag(Long.parseLong(deleted.version())))
                .body(deleted);
    }

    private static PlatformAdminService.Write toServiceWrite(PlatformWrite write) {
        if (write == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        PlatformAdminService.ProofConfig proofConfig =
                write.proofConfig() == null
                        ? null
                        : new PlatformAdminService.ProofConfig(
                                write.proofConfig().certificateFingerprint(),
                                write.proofConfig().proxyNetwork(),
                                write.proofConfig().secret());
        return new PlatformAdminService.Write(
                write.code(),
                write.name(),
                write.tier(),
                write.catalogMode(),
                write.proofMode(),
                write.status(),
                proofConfig);
    }

    private static PlatformDirectoryService.PlatformView toView(
            PlatformAdminService.PlatformView view) {
        return new PlatformDirectoryService.PlatformView(
                view.id(),
                view.code(),
                view.name(),
                view.tier(),
                view.status(),
                view.catalogMode(),
                view.proofMode(),
                view.proofConfigured(),
                view.scopeVersion(),
                view.securityVersion(),
                view.version());
    }

    private static String idempotencyKey(HttpServletRequest request) {
        String key = request.getHeader("Idempotency-Key");
        if (key == null || key.isBlank()) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        return key.trim();
    }

    private static Long parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            long value = Long.parseLong(cursor);
            if (value <= 0) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
            return value;
        } catch (NumberFormatException error) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
    }
}
