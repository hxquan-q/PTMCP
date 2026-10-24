package local.ptmcp.admin.api;

import jakarta.servlet.http.HttpServletRequest;

import local.ptmcp.persistence.org.OrgStore;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/** /api/v1/admin/users CRUD plus status/roles/department moves; DTOs are explicit. */
@RestController
@ConditionalOnProperty("ptmcp.admin.datasource.url")
@RequestMapping("/api/v1/admin/users")
public class UserAdminController {
    private final OrgStore orgStore;

    public UserAdminController(OrgStore orgStore) {
        this.orgStore = orgStore;
    }

    public record UserWrite(
            String username,
            String displayName,
            Long deptId,
            List<Long> roleIds,
            Boolean enabled) {}

    public record UserRead(
            long id,
            String username,
            String displayName,
            long deptId,
            boolean enabled,
            List<Long> roleIds,
            long version) {}

    public record UserStatusWrite(String status) {}

    public record UserDepartmentWrite(Long departmentId) {}

    @GetMapping
    public List<UserRead> list(
            @RequestParam(required = false) Long departmentId,
            @RequestParam(required = false) String keyword,
            HttpServletRequest request) {
        ManagementScope.requirePlatformAdmin(request);
        return orgStore.listUsers().stream()
                .filter(user -> departmentId == null || user.deptId() == departmentId)
                .filter(
                        user ->
                                keyword == null
                                        || keyword.isBlank()
                                        || user.username().contains(keyword)
                                        || user.displayName().contains(keyword))
                .map(UserAdminController::toRead)
                .toList();
    }

    @PostMapping
    public ResponseEntity<UserRead> create(
            @RequestBody UserWrite write, HttpServletRequest request) {
        ManagementScope.requirePlatformAdmin(request);
        if (write.username() == null
                || write.displayName() == null
                || write.deptId() == null
                || write.enabled() == null) {
            throw new local.ptmcp.common.error.DomainException(
                    local.ptmcp.common.error.ErrorCode.INVALID_ARGUMENT);
        }
        UserRead created =
                toRead(
                        orgStore.createUser(
                                write.username(),
                                write.displayName(),
                                write.deptId(),
                                "external",
                                write.roleIds() == null ? List.of() : write.roleIds(),
                                write.enabled(),
                                actorId(request)));
        return ResponseEntity.created(URI.create("/api/v1/admin/users/" + created.id()))
                .body(created);
    }

    @GetMapping("/{id}")
    public UserRead get(@PathVariable long id, HttpServletRequest request) {
        ManagementScope.requirePlatformAdmin(request);
        return toRead(orgStore.getUser(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<UserRead> update(
            @PathVariable long id, @RequestBody UserWrite write, HttpServletRequest request) {
        long expectedVersion = IfMatch.requireVersion(request);
        ManagementScope.requirePlatformAdmin(request);
        if (write.displayName() == null || write.deptId() == null || write.enabled() == null) {
            throw new local.ptmcp.common.error.DomainException(
                    local.ptmcp.common.error.ErrorCode.INVALID_ARGUMENT);
        }
        UserRead updated =
                toRead(
                        orgStore.updateUser(
                                id,
                                write.displayName(),
                                write.deptId(),
                                null,
                                null,
                                null,
                                write.enabled(),
                                write.roleIds(),
                                expectedVersion,
                                actorId(request)));
        return ResponseEntity.ok().eTag(IfMatch.etag(updated.version())).body(updated);
    }

    @PutMapping("/{id}/status")
    public ResponseEntity<UserRead> setStatus(
            @PathVariable long id, @RequestBody UserStatusWrite write, HttpServletRequest request) {
        long expectedVersion = IfMatch.requireVersion(request);
        ManagementScope.requirePlatformAdmin(request);
        if (!"ACTIVE".equals(write.status()) && !"DISABLED".equals(write.status())) {
            throw new local.ptmcp.common.error.DomainException(
                    local.ptmcp.common.error.ErrorCode.INVALID_ARGUMENT);
        }
        UserRead updated =
                toRead(
                        orgStore.updateUser(
                                id,
                                orgStore.getUser(id).displayName(),
                                orgStore.getUser(id).deptId(),
                                null,
                                null,
                                null,
                                "ACTIVE".equals(write.status()),
                                null,
                                expectedVersion,
                                actorId(request)));
        return ResponseEntity.ok().eTag(IfMatch.etag(updated.version())).body(updated);
    }

    @PutMapping("/{id}/roles")
    public ResponseEntity<UserRead> setRoles(
            @PathVariable long id, @RequestBody UserWrite write, HttpServletRequest request) {
        long expectedVersion = IfMatch.requireVersion(request);
        ManagementScope.requirePlatformAdmin(request);
        if (write.roleIds() == null) {
            throw new local.ptmcp.common.error.DomainException(
                    local.ptmcp.common.error.ErrorCode.INVALID_ARGUMENT);
        }
        OrgStore.UserRow current = orgStore.getUser(id);
        UserRead updated =
                toRead(
                        orgStore.updateUser(
                                id,
                                current.displayName(),
                                current.deptId(),
                                current.email(),
                                current.phoneNumber(),
                                current.remark(),
                                null,
                                write.roleIds(),
                                expectedVersion,
                                actorId(request)));
        return ResponseEntity.ok().eTag(IfMatch.etag(updated.version())).body(updated);
    }

    @PutMapping("/{id}/department")
    public ResponseEntity<UserRead> setDepartment(
            @PathVariable long id,
            @RequestBody UserDepartmentWrite write,
            HttpServletRequest request) {
        long expectedVersion = IfMatch.requireVersion(request);
        ManagementScope.requirePlatformAdmin(request);
        if (write.departmentId() == null) {
            throw new local.ptmcp.common.error.DomainException(
                    local.ptmcp.common.error.ErrorCode.INVALID_ARGUMENT);
        }
        OrgStore.UserRow current = orgStore.getUser(id);
        UserRead updated =
                toRead(
                        orgStore.updateUser(
                                id,
                                current.displayName(),
                                write.departmentId(),
                                current.email(),
                                current.phoneNumber(),
                                current.remark(),
                                null,
                                null,
                                expectedVersion,
                                actorId(request)));
        return ResponseEntity.ok().eTag(IfMatch.etag(updated.version())).body(updated);
    }

    private static long actorId(HttpServletRequest request) {
        return AdminPrincipal.require(request).userId();
    }

    private static UserRead toRead(OrgStore.UserRow row) {
        return new UserRead(
                row.id(),
                row.username(),
                row.displayName(),
                row.deptId(),
                row.enabled(),
                row.roleIds(),
                row.version());
    }
}
