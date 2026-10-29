package local.ptmcp.admin.api;

import jakarta.servlet.http.HttpServletRequest;

import local.ptmcp.admin.datasource.DatasourceAdminService;
import local.ptmcp.admin.datasource.DatasourceJobService;
import local.ptmcp.admin.web.RequestIdFilter;
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

/**
 * /api/v1/admin/datasources. Credentials only travel in write bodies and never appear in a
 * response; probes and syncs are jobs, not inline SQL execution.
 */
@RestController
@ConditionalOnProperty("ptmcp.admin.datasource.url")
@RequestMapping("/api/v1/admin/datasources")
public class DatasourceAdminController {
    private final DatasourceAdminService datasources;
    private final DatasourceJobService jobs;
    private final IdempotencyRecorder idempotency;

    public DatasourceAdminController(
            DatasourceAdminService datasources,
            DatasourceJobService jobs,
            IdempotencyRecorder idempotency) {
        this.datasources = datasources;
        this.jobs = jobs;
        this.idempotency = idempotency;
    }

    public record TestRequest(DatasourceAdminService.Write draft) {}

    public record SyncRequest(String mode) {}

    public record PageView(java.util.List<DatasourceAdminService.Read> items, String nextCursor) {}

    @AdminAction("ptmcp:datasource:list")
    @GetMapping
    public ResponseEntity<PageView> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String engine,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer pageSize,
            HttpServletRequest request) {
        ManagementScope.requirePlatformAdmin(request);
        Long afterId = parseCursor(cursor);
        int size = pageSize == null ? 20 : pageSize;
        DatasourceAdminService.Page page = datasources.list(keyword, engine, status, afterId, size);
        return ResponseEntity.ok(new PageView(page.items(), page.nextCursor()));
    }

    @AdminAction("ptmcp:datasource:read")
    @GetMapping("/{id}")
    public ResponseEntity<DatasourceAdminService.Read> get(
            @PathVariable long id, HttpServletRequest request) {
        ManagementScope.requirePlatformAdmin(request);
        DatasourceAdminService.Read read = datasources.get(id);
        return ResponseEntity.ok()
                .eTag(IfMatch.etag(Long.parseLong(read.configVersion())))
                .body(read);
    }

    @AdminAction("ptmcp:datasource:create")
    @PostMapping
    public ResponseEntity<byte[]> create(
            @RequestBody(required = false) byte[] body, HttpServletRequest request) {
        AdminPrincipal principal = ManagementScope.requirePlatformAdminPrincipal(request);
        DatasourceAdminService.Write write =
                StrictJson.parse(body, DatasourceAdminService.Write.class);
        return IdempotentWrites.execute(
                idempotency,
                principal.userId(),
                "datasources.create",
                idempotencyKey(request),
                body,
                () -> {
                    DatasourceAdminService.Read created =
                            datasources.create(write, principal.userId());
                    return ResponseEntity.created(
                                    URI.create("/api/v1/admin/datasources/" + created.id()))
                            .eTag(IfMatch.etag(Long.parseLong(created.configVersion())))
                            .body(created);
                });
    }

    @AdminAction("ptmcp:datasource:update")
    @PutMapping("/{id}")
    public ResponseEntity<DatasourceAdminService.Read> update(
            @PathVariable long id,
            @RequestBody(required = false) byte[] body,
            HttpServletRequest request) {
        long expectedVersion = IfMatch.requireVersion(request);
        AdminPrincipal principal = ManagementScope.requirePlatformAdminPrincipal(request);
        DatasourceAdminService.Write write =
                StrictJson.parse(body, DatasourceAdminService.Write.class);
        DatasourceAdminService.Read updated =
                datasources.update(
                        id,
                        expectedVersion,
                        write,
                        principal.userId(),
                        RequestIdFilter.requestId(request));
        return ResponseEntity.ok()
                .eTag(IfMatch.etag(Long.parseLong(updated.configVersion())))
                .body(updated);
    }

    @AdminAction("ptmcp:datasource:delete")
    @DeleteMapping("/{id}")
    public ResponseEntity<DatasourceAdminService.DeleteView> delete(
            @PathVariable long id, HttpServletRequest request) {
        long expectedVersion = IfMatch.requireVersion(request);
        AdminPrincipal principal = ManagementScope.requirePlatformAdminPrincipal(request);
        return ResponseEntity.ok(datasources.delete(id, expectedVersion, principal.userId()));
    }

    @AdminAction("ptmcp:datasource:test")
    @PostMapping("/{id}/test")
    public ResponseEntity<byte[]> test(
            @PathVariable long id,
            @RequestBody(required = false) byte[] body,
            HttpServletRequest request) {
        AdminPrincipal principal = ManagementScope.requirePlatformAdminPrincipal(request);
        DatasourceAdminService.Write draft =
                body == null || body.length == 0
                        ? null
                        : StrictJson.parse(body, TestRequest.class).draft();
        return IdempotentWrites.execute(
                idempotency,
                principal.userId(),
                "datasources.test:" + id,
                idempotencyKey(request),
                body,
                () -> ResponseEntity.status(202).body(jobs.test(id, draft, principal.userId())));
    }

    @AdminAction("ptmcp:datasource:sync")
    @PostMapping("/{id}/sync")
    public ResponseEntity<byte[]> sync(
            @PathVariable long id,
            @RequestBody(required = false) byte[] body,
            HttpServletRequest request) {
        AdminPrincipal principal = ManagementScope.requirePlatformAdminPrincipal(request);
        SyncRequest sync = StrictJson.parse(body, SyncRequest.class);
        if (!"FULL".equals(sync.mode())) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        return IdempotentWrites.execute(
                idempotency,
                principal.userId(),
                "datasources.sync:" + id,
                idempotencyKey(request),
                body,
                () -> ResponseEntity.status(202).body(jobs.sync(id, principal.userId())));
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
