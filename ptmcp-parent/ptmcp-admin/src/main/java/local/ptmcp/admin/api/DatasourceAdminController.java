package local.ptmcp.admin.api;

import jakarta.servlet.http.HttpServletRequest;

import local.ptmcp.admin.datasource.DatasourceAdminService;
import local.ptmcp.admin.datasource.DatasourceJobService;
import local.ptmcp.admin.web.RequestIdFilter;
import local.ptmcp.admin.web.StrictJson;
import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.org.DelegationStore;
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
    /** One capability code per action; api-spec forbids mapping one CRUD code to all actions. */
    private static final String LIST = "ptmcp:datasource:list";

    private static final String READ = "ptmcp:datasource:read";
    private static final String CREATE = "ptmcp:datasource:create";
    private static final String UPDATE = "ptmcp:datasource:update";
    private static final String DELETE = "ptmcp:datasource:delete";
    private static final String TEST = "ptmcp:datasource:test";
    private static final String SYNC = "ptmcp:datasource:sync";

    private final DatasourceAdminService datasources;
    private final DatasourceJobService jobs;
    private final IdempotencyRecorder idempotency;
    private final DelegationStore delegations;

    public DatasourceAdminController(
            DatasourceAdminService datasources,
            DatasourceJobService jobs,
            IdempotencyRecorder idempotency,
            DelegationStore delegations) {
        this.datasources = datasources;
        this.jobs = jobs;
        this.idempotency = idempotency;
        this.delegations = delegations;
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
        ManagementScope.requirePlatformAdminPrincipal(request, delegations, LIST);
        Long afterId = parseCursor(cursor);
        int size = pageSize == null ? 20 : pageSize;
        DatasourceAdminService.Page page = datasources.list(keyword, engine, status, afterId, size);
        return ResponseEntity.ok(new PageView(page.items(), page.nextCursor()));
    }

    @AdminAction("ptmcp:datasource:read")
    @GetMapping("/{id}")
    public ResponseEntity<DatasourceAdminService.Read> get(
            @PathVariable long id, HttpServletRequest request) {
        ManagementScope.requirePlatformAdminPrincipal(request, delegations, READ);
        DatasourceAdminService.Read read = datasources.get(id);
        return ResponseEntity.ok()
                .eTag(IfMatch.etag(Long.parseLong(read.configVersion())))
                .body(read);
    }

    @AdminAction("ptmcp:datasource:create")
    @PostMapping
    public ResponseEntity<byte[]> create(
            @RequestBody(required = false) byte[] body, HttpServletRequest request) {
        AdminPrincipal principal =
                ManagementScope.requirePlatformAdminPrincipal(request, delegations, CREATE);
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
        AdminPrincipal principal =
                ManagementScope.requirePlatformAdminPrincipal(request, delegations, UPDATE);
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
        AdminPrincipal principal =
                ManagementScope.requirePlatformAdminPrincipal(request, delegations, DELETE);
        return ResponseEntity.ok(datasources.delete(id, expectedVersion, principal.userId()));
    }

    @AdminAction("ptmcp:datasource:test")
    @PostMapping("/{id}/test")
    public ResponseEntity<byte[]> test(
            @PathVariable long id,
            @RequestBody(required = false) byte[] body,
            HttpServletRequest request) {
        AdminPrincipal principal =
                ManagementScope.requirePlatformAdminPrincipal(request, delegations, TEST);
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
                () ->
                        ResponseEntity.status(202)
                                .body(
                                        jobs.test(
                                                id,
                                                draft,
                                                principal.userId(),
                                                RequestIdFilter.requestId(request))));
    }

    @AdminAction("ptmcp:datasource:update")
    @PostMapping("/{id}/candidates/{candidateId}/publish")
    public ResponseEntity<byte[]> publish(
            @PathVariable long id,
            @PathVariable long candidateId,
            @RequestBody(required = false) byte[] body,
            HttpServletRequest request) {
        // The publish contract is an empty body; anything else is a client mistake, not a variant.
        if (body != null && body.length > 0) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        long expectedVersion = IfMatch.requireVersion(request);
        AdminPrincipal principal =
                ManagementScope.requirePlatformAdminPrincipal(request, delegations, UPDATE);
        return IdempotentWrites.execute(
                idempotency,
                principal.userId(),
                "datasources.publish:" + id + ":" + candidateId,
                idempotencyKey(request),
                body,
                () -> {
                    DatasourceAdminService.Read published =
                            datasources.publish(
                                    id,
                                    candidateId,
                                    expectedVersion,
                                    principal.userId(),
                                    RequestIdFilter.requestId(request));
                    return ResponseEntity.ok()
                            .eTag(IfMatch.etag(Long.parseLong(published.configVersion())))
                            .body(published);
                });
    }

    @AdminAction("ptmcp:datasource:sync")
    @PostMapping("/{id}/sync")
    public ResponseEntity<byte[]> sync(
            @PathVariable long id,
            @RequestBody(required = false) byte[] body,
            HttpServletRequest request) {
        AdminPrincipal principal =
                ManagementScope.requirePlatformAdminPrincipal(request, delegations, SYNC);
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
