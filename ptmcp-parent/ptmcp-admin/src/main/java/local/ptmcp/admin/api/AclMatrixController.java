package local.ptmcp.admin.api;

import jakarta.servlet.http.HttpServletRequest;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.org.AclGrantStore;
import local.ptmcp.persistence.org.DelegationStore;
import local.ptmcp.persistence.org.OrgStore;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * GET /api/v1/admin/acl/matrix: the grant matrix with effect, validity window, grant version and
 * resource generation (plus live generation and the stale flag) displayed per row. Every filter is
 * applied inside the store query — the surface never post-filters. Platform admins see the whole
 * matrix; delegated admins must scope the query to one resource covered by their delegation and the
 * "acl:list" action, otherwise the request is denied without any rows.
 */
@RestController
@ConditionalOnProperty("ptmcp.admin.datasource.url")
@RequestMapping("/api/v1/admin/acl/matrix")
public class AclMatrixController {
    private final AclGrantStore grantStore;
    private final DelegationStore delegations;
    private final OrgStore orgStore;

    public AclMatrixController(
            AclGrantStore grantStore, DelegationStore delegations, OrgStore orgStore) {
        this.grantStore = grantStore;
        this.delegations = delegations;
        this.orgStore = orgStore;
    }

    public record MatrixRead(
            long id,
            String subjectType,
            long subjectId,
            String resourceType,
            long resourceId,
            long resourceGeneration,
            String action,
            String effect,
            boolean includeDescendants,
            String validFrom,
            String validTo,
            String status,
            long version,
            long liveGeneration,
            boolean stale) {}

    @GetMapping
    public List<MatrixRead> matrix(
            @RequestParam(required = false) String subjectType,
            @RequestParam(required = false) Long subjectId,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) Long resourceId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String effect,
            @RequestParam(required = false) Boolean stale,
            @RequestParam(defaultValue = "200") int limit,
            HttpServletRequest request) {
        AdminPrincipal principal = AdminPrincipal.require(request);
        if (!principal.platformAdmin()) {
            // Delegated admins must pin the query to one resource their delegation covers; an
            // unscoped matrix would leak grants outside the delegation.
            if (resourceType == null || resourceId == null) {
                throw new DomainException(ErrorCode.MANAGEMENT_SCOPE_DENIED);
            }
            ManagementScope.requireGrantOperator(
                    request, delegations, orgStore, "acl:list", resourceType, resourceId, null);
        }
        List<AclGrantStore.MatrixRow> rows =
                grantStore.matrix(
                        subjectType,
                        subjectId,
                        resourceType,
                        resourceId,
                        status,
                        effect,
                        stale,
                        limit);
        return rows.stream().map(AclMatrixController::toRead).toList();
    }

    private static MatrixRead toRead(AclGrantStore.MatrixRow row) {
        return new MatrixRead(
                row.id(),
                row.subjectType(),
                row.subjectId(),
                row.resourceType(),
                row.resourceId(),
                row.resourceGeneration(),
                row.action(),
                row.effect(),
                row.includeDescendants(),
                row.validFrom() == null ? null : row.validFrom().toString(),
                row.validTo() == null ? null : row.validTo().toString(),
                row.status(),
                row.version(),
                row.liveGeneration(),
                row.stale());
    }
}
