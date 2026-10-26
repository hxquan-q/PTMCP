package local.ptmcp.admin.api;

import jakarta.servlet.http.HttpServletRequest;

import local.ptmcp.admin.web.StrictJson;
import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.org.IdempotencyRecorder;
import local.ptmcp.persistence.org.OrgStore;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/** /api/v1/admin/departments CRUD; response bodies are explicit DTOs (R<T> wrapper pending). */
@RestController
@ConditionalOnProperty("ptmcp.admin.datasource.url")
@RequestMapping("/api/v1/admin/departments")
public class DepartmentAdminController {
    private final OrgStore orgStore;
    private final IdempotencyRecorder idempotency;

    public DepartmentAdminController(OrgStore orgStore, IdempotencyRecorder idempotency) {
        this.orgStore = orgStore;
        this.idempotency = idempotency;
    }

    public record DepartmentWrite(Long parentId, String name, boolean enabled) {}

    public record DepartmentRead(
            long id, Long parentId, String name, boolean enabled, long version, String ancestors) {}

    @GetMapping
    public List<DepartmentRead> list(HttpServletRequest request) {
        ManagementScope.requirePlatformAdmin(request);
        return orgStore.listDepartments().stream().map(DepartmentAdminController::toRead).toList();
    }

    @PostMapping
    public ResponseEntity<byte[]> create(
            @RequestBody(required = false) byte[] body, HttpServletRequest request) {
        ManagementScope.requirePlatformAdmin(request);
        DepartmentWrite write = StrictJson.parse(body, DepartmentWrite.class);
        return IdempotentWrites.execute(
                idempotency,
                actorId(request),
                "departments.create",
                idempotencyKey(request),
                body,
                () -> {
                    if (write.name() == null) {
                        throw new DomainException(ErrorCode.INVALID_ARGUMENT);
                    }
                    DepartmentRead created =
                            toRead(
                                    orgStore.createDepartment(
                                            write.parentId(),
                                            write.name(),
                                            write.enabled(),
                                            actorId(request)));
                    return ResponseEntity.created(
                                    URI.create("/api/v1/admin/departments/" + created.id()))
                            .body(created);
                });
    }

    private static String idempotencyKey(HttpServletRequest request) {
        String key = request.getHeader("Idempotency-Key");
        if (key == null || key.isBlank()) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        return key.trim();
    }

    @GetMapping("/{id}")
    public DepartmentRead get(@PathVariable long id, HttpServletRequest request) {
        ManagementScope.requirePlatformAdmin(request);
        return toRead(orgStore.getDepartment(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<DepartmentRead> update(
            @PathVariable long id, @RequestBody DepartmentWrite write, HttpServletRequest request) {
        long expectedVersion = IfMatch.requireVersion(request);
        ManagementScope.requirePlatformAdmin(request);
        DepartmentRead current = toRead(orgStore.getDepartment(id));
        DepartmentRead updated;
        if (write.parentId() == null
                ? current.parentId() != null
                : !write.parentId().equals(current.parentId())) {
            orgStore.moveDepartment(id, write.parentId(), expectedVersion, actorId(request));
            expectedVersion = current.version() + 1;
        }
        updated =
                toRead(
                        orgStore.updateDepartment(
                                id,
                                write.name(),
                                write.enabled(),
                                expectedVersion,
                                actorId(request)));
        return ResponseEntity.ok().eTag(IfMatch.etag(updated.version())).body(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<DepartmentRead> delete(
            @PathVariable long id, HttpServletRequest request) {
        long expectedVersion = IfMatch.requireVersion(request);
        ManagementScope.requirePlatformAdmin(request);
        orgStore.deleteDepartment(id, expectedVersion, actorId(request));
        return ResponseEntity.noContent().build();
    }

    private static long actorId(HttpServletRequest request) {
        return AdminPrincipal.require(request).userId();
    }

    private static DepartmentRead toRead(OrgStore.DepartmentRow row) {
        return new DepartmentRead(
                row.id(),
                row.parentId(),
                row.name(),
                row.enabled(),
                row.version(),
                row.ancestors());
    }
}
