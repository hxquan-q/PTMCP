package local.ptmcp.admin.api;

import jakarta.servlet.http.HttpServletRequest;

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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * /api/v1/admin/delegations. Platform-admin surface for the admin_delegation records (the
 * delegation-aware management scope for department admins is a later slice and stays explicitly out
 * of this controller). Create resolves the current resource generation, blocks self- delegation,
 * pins the stored actions to a delegable subset of the caller's own codes and writes one
 * admin_operation_log entry; revoke is optimistic via If-Match.
 */
@RestController
@ConditionalOnProperty("ptmcp.admin.datasource.url")
@RequestMapping("/api/v1/admin/delegations")
public class DelegationAdminController {
    /** Only capability codes a delegation may carry; mirrors the console's action options. */
    private static final Set<String> DELEGABLE_PREFIXES =
            Set.of("ptmcp:user:", "ptmcp:department:", "ptmcp:role:", "ptmcp:acl:");

    private final DelegationStore delegations;
    private final IdempotencyRecorder idempotency;

    public DelegationAdminController(DelegationStore delegations, IdempotencyRecorder idempotency) {
        this.delegations = delegations;
        this.idempotency = idempotency;
    }

    public record DelegationWrite(
            Long userId,
            Long departmentRootId,
            String resourceType,
            Long resourceId,
            List<String> allowedActions,
            Instant validFrom,
            Instant validTo) {}

    public record DelegationView(
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

    public record PageView(List<DelegationView> items, String nextCursor) {}

    @GetMapping
    public ResponseEntity<PageView> list(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer pageSize,
            HttpServletRequest request) {
        ManagementScope.requirePlatformAdmin(request);
        int limit = pageLimit(pageSize);
        Instant now = Instant.now();
        List<DelegationView> items =
                delegations.list(userId, status, resourceType, cursor, pageSize).stream()
                        .map(row -> view(row, now))
                        .toList();
        // The store fetched limit+1 rows: an overflow page yields the cursor of the last kept row.
        String nextCursor = null;
        if (items.size() > limit) {
            items = items.subList(0, limit);
            nextCursor = String.valueOf(items.getLast().id());
        }
        return ResponseEntity.ok(new PageView(items, nextCursor));
    }

    @PostMapping
    public ResponseEntity<byte[]> create(
            @RequestBody(required = false) byte[] body, HttpServletRequest request) {
        AdminPrincipal principal = ManagementScope.requirePlatformAdminPrincipal(request);
        DelegationWrite write = StrictJson.parse(body, DelegationWrite.class);
        return IdempotentWrites.execute(
                idempotency,
                principal.userId(),
                "delegations.create",
                idempotencyKey(request),
                body,
                () -> {
                    DelegationStore.DelegationRow created =
                            delegations.create(
                                    principal.userId(),
                                    UUID.randomUUID().toString(),
                                    draft(write),
                                    delegatableActions(principal.userId()));
                    return ResponseEntity.created(
                                    URI.create("/api/v1/admin/delegations/" + created.id()))
                            .eTag(IfMatch.etag(created.version()))
                            .body(view(created, Instant.now()));
                });
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<DelegationView> revoke(
            @PathVariable long id, HttpServletRequest request) {
        long expectedVersion = IfMatch.requireVersion(request);
        AdminPrincipal principal = ManagementScope.requirePlatformAdminPrincipal(request);
        String requestId = UUID.randomUUID().toString();
        DelegationStore.DelegationRow revoked =
                delegations.revoke(id, expectedVersion, principal.userId(), requestId);
        return ResponseEntity.ok()
                .eTag(IfMatch.etag(revoked.version()))
                .body(view(revoked, Instant.now()));
    }

    private static DelegationStore.DelegationDraft draft(DelegationWrite write) {
        if (write == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        return new DelegationStore.DelegationDraft(
                requireId(write.userId()),
                requireId(write.departmentRootId()),
                write.resourceType(),
                requireId(write.resourceId()),
                write.allowedActions() == null ? List.of() : write.allowedActions(),
                write.validFrom(),
                write.validTo());
    }

    private static long requireId(Long value) {
        if (value == null || value <= 0) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        return value;
    }

    /** The delegable set is the caller's own org/ACL capability codes (AC-07 bound). */
    private Set<String> delegatableActions(long userId) {
        Set<String> delegatable = new LinkedHashSet<>();
        for (String permission : delegations.permissionsOf(userId)) {
            for (String prefix : DELEGABLE_PREFIXES) {
                if (permission.startsWith(prefix)) {
                    delegatable.add(permission);
                    break;
                }
            }
        }
        return delegatable;
    }

    private static DelegationView view(DelegationStore.DelegationRow row, Instant now) {
        return new DelegationView(
                row.id(),
                row.userId(),
                row.departmentRootId(),
                row.resourceType(),
                row.resourceId(),
                row.resourceGeneration(),
                row.allowedActions(),
                row.validFrom(),
                row.validTo(),
                DelegationStore.displayStatus(row, now),
                row.version());
    }

    private static int pageLimit(Integer pageSize) {
        if (pageSize == null) {
            return 20;
        }
        if (pageSize < 1 || pageSize > 100) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        return pageSize;
    }

    private static String idempotencyKey(HttpServletRequest request) {
        String key = request.getHeader("Idempotency-Key");
        if (key == null || key.isBlank()) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        return key.trim();
    }
}
