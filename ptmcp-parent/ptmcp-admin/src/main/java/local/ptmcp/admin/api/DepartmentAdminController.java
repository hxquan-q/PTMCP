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

    @AdminAction("ptmcp:department:list")
    @GetMapping
    public List<DepartmentRead> list(HttpServletRequest request) {
        return orgStore.listDepartments().stream().map(DepartmentAdminController::toRead).toList();
    }

    @AdminAction("ptmcp:department:create")
    @PostMapping
    public ResponseEntity<byte[]> create(
            @RequestBody(required = false) byte[] body, HttpServletRequest request) {
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

    @AdminAction("ptmcp:department:read")
    @GetMapping("/{id}")
    public DepartmentRead get(@PathVariable long id, HttpServletRequest request) {
        return toRead(orgStore.getDepartment(id));
    }

    @AdminAction("ptmcp:department:update")
    @PutMapping("/{id}")
    public ResponseEntity<DepartmentRead> update(
            @PathVariable long id, @RequestBody DepartmentWrite write, HttpServletRequest request) {
        long expectedVersion = IfMatch.requireVersion(request);
        OrgStore.DepartmentRow before = orgStore.getDepartment(id);
        if (before.version() != expectedVersion) {
            throw new DomainException(ErrorCode.VERSION_CONFLICT);
        }
        DepartmentRead updated =
                toRead(
                        orgStore.editDepartment(
                                id,
                                write.parentId(),
                                write.name(),
                                write.enabled(),
                                expectedVersion,
                                actorId(request)));
        return ResponseEntity.ok().eTag(IfMatch.etag(updated.version())).body(updated);
    }

    @AdminAction("ptmcp:department:delete")
    @DeleteMapping("/{id}")
    public ResponseEntity<DepartmentRead> delete(
            @PathVariable long id, HttpServletRequest request) {
        long expectedVersion = IfMatch.requireVersion(request);
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
