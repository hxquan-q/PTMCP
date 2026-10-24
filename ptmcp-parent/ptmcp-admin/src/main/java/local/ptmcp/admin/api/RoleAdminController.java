package local.ptmcp.admin.api;

import jakarta.servlet.http.HttpServletRequest;

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

/** /api/v1/admin/roles CRUD; response bodies are explicit DTOs (R<T> wrapper pending). */
@RestController
@ConditionalOnProperty("ptmcp.admin.datasource.url")
@RequestMapping("/api/v1/admin/roles")
public class RoleAdminController {
    private final OrgStore orgStore;

    public RoleAdminController(OrgStore orgStore) {
        this.orgStore = orgStore;
    }

    public record RoleWrite(
            String key, String name, Boolean enabled, List<String> permissionCodes) {}

    public record RoleRead(
            long id,
            String key,
            String name,
            boolean enabled,
            List<String> permissionCodes,
            long version) {}

    @GetMapping
    public List<RoleRead> list(HttpServletRequest request) {
        ManagementScope.requirePlatformAdmin(request);
        return orgStore.listRoles().stream().map(RoleAdminController::toRead).toList();
    }

    @PostMapping
    public ResponseEntity<RoleRead> create(
            @RequestBody RoleWrite write, HttpServletRequest request) {
        ManagementScope.requirePlatformAdmin(request);
        if (write.key() == null || write.name() == null || write.enabled() == null) {
            throw new local.ptmcp.common.error.DomainException(
                    local.ptmcp.common.error.ErrorCode.INVALID_ARGUMENT);
        }
        RoleRead created =
                toRead(
                        orgStore.createRole(
                                write.key(),
                                write.name(),
                                write.enabled(),
                                write.permissionCodes() == null
                                        ? List.of()
                                        : write.permissionCodes(),
                                actorId(request)));
        return ResponseEntity.created(URI.create("/api/v1/admin/roles/" + created.id()))
                .body(created);
    }

    @GetMapping("/{id}")
    public RoleRead get(@PathVariable long id, HttpServletRequest request) {
        ManagementScope.requirePlatformAdmin(request);
        return toRead(orgStore.getRole(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<RoleRead> update(
            @PathVariable long id, @RequestBody RoleWrite write, HttpServletRequest request) {
        long expectedVersion = IfMatch.requireVersion(request);
        ManagementScope.requirePlatformAdmin(request);
        if (write.name() == null) {
            throw new local.ptmcp.common.error.DomainException(
                    local.ptmcp.common.error.ErrorCode.INVALID_ARGUMENT);
        }
        RoleRead updated =
                toRead(
                        orgStore.updateRole(
                                id,
                                write.name(),
                                write.enabled(),
                                write.permissionCodes(),
                                expectedVersion,
                                actorId(request)));
        return ResponseEntity.ok().eTag(IfMatch.etag(updated.version())).body(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id, HttpServletRequest request) {
        long expectedVersion = IfMatch.requireVersion(request);
        ManagementScope.requirePlatformAdmin(request);
        orgStore.deleteRole(id, expectedVersion, actorId(request));
        return ResponseEntity.noContent().build();
    }

    private static long actorId(HttpServletRequest request) {
        return AdminPrincipal.require(request).userId();
    }

    private static RoleRead toRead(OrgStore.RoleRow row) {
        return new RoleRead(
                row.id(),
                row.key(),
                row.name(),
                row.enabled(),
                row.permissionCodes(),
                row.version());
    }
}
