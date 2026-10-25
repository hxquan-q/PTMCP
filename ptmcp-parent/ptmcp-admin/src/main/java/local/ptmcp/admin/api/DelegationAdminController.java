package local.ptmcp.admin.api;

import jakarta.servlet.http.HttpServletRequest;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.org.DelegationStore;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.List;

/**
 * /api/v1/admin/delegations: platform-admin management of delegated administrators. Creating a
 * delegation that targets the operator themself is a self-escalation and never persists; every
 * mutation is audited by the store in the same transaction. Revocation is optimistic via If-Match.
 */
@RestController
@ConditionalOnProperty("ptmcp.admin.datasource.url")
@RequestMapping("/api/v1/admin/delegations")
public class DelegationAdminController {
    private final DelegationStore delegationStore;

    public DelegationAdminController(DelegationStore delegationStore) {
        this.delegationStore = delegationStore;
    }

    public record DelegationWrite(
            Long userId,
            Long departmentRootId,
            String resourceType,
            Long resourceId,
            Long resourceGeneration,
            List<String> allowedActions,
            Instant validFrom,
            Instant validTo) {}

    public record DelegationRead(
            long id,
            long userId,
            long departmentRootId,
            String resourceType,
            long resourceId,
            long resourceGeneration,
            List<String> allowedActions,
            Instant validFrom,
            Instant validTo,
            String status,
            long version) {}

    @GetMapping
    public List<DelegationRead> list(@RequestParam long userId, HttpServletRequest request) {
        ManagementScope.requirePlatformAdmin(request);
        return delegationStore.list(userId, 200).stream()
                .map(DelegationAdminController::toRead)
                .toList();
    }

    @PostMapping
    public ResponseEntity<DelegationRead> create(
            @RequestBody DelegationWrite write, HttpServletRequest request) {
        AdminPrincipal principal = ManagementScope.requirePlatformAdminPrincipal(request);
        if (write.userId() == null
                || write.departmentRootId() == null
                || write.resourceType() == null
                || write.resourceId() == null
                || write.resourceGeneration() == null
                || write.validFrom() == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        DelegationStore.DelegationRecord created =
                delegationStore.create(
                        new DelegationStore.Draft(
                                write.userId(),
                                write.departmentRootId(),
                                write.resourceType(),
                                write.resourceId(),
                                write.resourceGeneration(),
                                write.allowedActions(),
                                write.validFrom(),
                                write.validTo()),
                        principal.userId());
        return ResponseEntity.created(URI.create("/api/v1/admin/delegations/" + created.id()))
                .eTag(IfMatch.etag(created.version()))
                .body(toRead(created));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<DelegationRead> revoke(
            @PathVariable long id, HttpServletRequest request) {
        long expectedVersion = IfMatch.requireVersion(request);
        AdminPrincipal principal = ManagementScope.requirePlatformAdminPrincipal(request);
        DelegationStore.DelegationRecord revoked =
                delegationStore.revoke(id, expectedVersion, principal.userId());
        return ResponseEntity.ok().eTag(IfMatch.etag(revoked.version())).body(toRead(revoked));
    }

    private static DelegationRead toRead(DelegationStore.DelegationRecord record) {
        return new DelegationRead(
                record.id(),
                record.userId(),
                record.departmentRootId(),
                record.resourceType(),
                record.resourceId(),
                record.resourceGeneration(),
                record.allowedActions(),
                record.validFrom(),
                record.validTo(),
                record.status(),
                record.version());
    }
}
