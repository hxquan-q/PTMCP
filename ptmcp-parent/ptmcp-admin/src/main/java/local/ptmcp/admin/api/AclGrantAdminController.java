package local.ptmcp.admin.api;

import jakarta.servlet.http.HttpServletRequest;

import local.ptmcp.common.authz.GrantRow;
import local.ptmcp.common.authz.PermissionGrant;
import local.ptmcp.common.authz.ResourceNode;
import local.ptmcp.common.authz.SubjectScope;
import local.ptmcp.common.authz.SubjectType;
import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.org.AclDirectory;
import local.ptmcp.persistence.org.AclGrantStore;
import local.ptmcp.persistence.org.DelegationStore;
import local.ptmcp.persistence.org.OrgStore;
import local.ptmcp.persistence.org.ResourceIds;
import local.ptmcp.persistence.org.SelfEscalationGuard;

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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * /api/v1/admin/acl/grants CRUD and /acl/preview. Every mutating grant operation first proves it
 * does not raise the caller's own effective META/READ over any tree node (self-privilege-
 * escalation, 403). The preview judges only — it never borrows a business connection.
 */
@RestController
@ConditionalOnProperty("ptmcp.admin.datasource.url")
@RequestMapping("/api/v1/admin/acl")
public class AclGrantAdminController {
    private final AclGrantStore grantStore;
    private final AclDirectory directory;
    private final DelegationStore delegations;
    private final OrgStore orgStore;

    public AclGrantAdminController(
            AclGrantStore grantStore,
            AclDirectory directory,
            DelegationStore delegations,
            OrgStore orgStore) {
        this.grantStore = grantStore;
        this.directory = directory;
        this.delegations = delegations;
        this.orgStore = orgStore;
    }

    public record GrantWrite(
            String subjectType,
            Long subjectId,
            String resourceType,
            Long resourceId,
            Long resourceGeneration,
            String action,
            String effect,
            Boolean includeDescendants,
            Instant validFrom,
            Instant validTo,
            String reason) {}

    public record GrantRead(
            long id,
            String subjectType,
            long subjectId,
            String resourceType,
            long resourceId,
            long resourceGeneration,
            String action,
            String effect,
            boolean includeDescendants,
            Instant validFrom,
            Instant validTo,
            String status,
            String reason,
            long version) {}

    public record PreviewRequest(
            Long subjectUserId, Long platformId, List<Long> resourceIds, Instant at) {}

    public record PreviewDecision(
            long resourceId, boolean meta, boolean read, List<Long> sourceGrantIds) {}

    public record PreviewResponse(
            List<PreviewDecision> decisions, Instant evaluatedAt, Instant nextBoundaryAt) {}

    @GetMapping("/grants")
    public List<GrantRead> list(
            @RequestParam(required = false) String subjectType,
            @RequestParam(required = false) Long subjectId,
            HttpServletRequest request) {
        ManagementScope.requirePlatformAdmin(request);
        return grantStore.list(subjectType, subjectId).stream()
                .map(AclGrantAdminController::toRead)
                .toList();
    }

    @PostMapping("/grants")
    public ResponseEntity<GrantRead> create(
            @RequestBody GrantWrite write, HttpServletRequest request) {
        AclGrantStore.AclGrantRecord draft = toDraft(write);
        Long targetDept = requireTargetDepartment(draft.subjectType(), draft.subjectId());
        AdminPrincipal principal =
                ManagementScope.requireGrantOperator(
                        request,
                        delegations,
                        orgStore,
                        "acl:grant",
                        draft.resourceType(),
                        draft.resourceId(),
                        targetDept);
        List<GrantRow> before = callerGrantsOrNull(directory, principal);
        if (before != null) {
            List<GrantRow> after = new ArrayList<>(before);
            after.add(toGrantRow(draft, Long.MAX_VALUE));
            assertNoEscalation(directory, principal, before, after);
        }
        AclGrantStore.AclGrantRecord created = grantStore.create(draft, principal.userId());
        return ResponseEntity.created(
                        java.net.URI.create("/api/v1/admin/acl/grants/" + created.id()))
                .body(toRead(created));
    }

    @GetMapping("/grants/{id}")
    public GrantRead get(@PathVariable long id, HttpServletRequest request) {
        ManagementScope.requirePlatformAdmin(request);
        return toRead(grantStore.get(id));
    }

    @PutMapping("/grants/{id}")
    public ResponseEntity<GrantRead> update(
            @PathVariable long id, @RequestBody GrantWrite write, HttpServletRequest request) {
        long expectedVersion = IfMatch.requireVersion(request);
        AclGrantStore.AclGrantRecord current = grantStore.get(id);
        if ("REVOKED".equals(current.status())) {
            throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        AclGrantStore.AclGrantRecord replacement = toDraft(write);
        Long updateDept =
                requireTargetDepartment(replacement.subjectType(), replacement.subjectId());
        AdminPrincipal principal =
                ManagementScope.requireGrantOperator(
                        request,
                        delegations,
                        orgStore,
                        "acl:grant",
                        replacement.resourceType(),
                        replacement.resourceId(),
                        updateDept);
        List<GrantRow> before = callerGrantsOrNull(directory, principal);
        if (before != null) {
            long encodedResource =
                    ResourceIds.encode(
                            ResourceIds.Type.valueOf(replacement.resourceType()),
                            replacement.resourceId());
            List<GrantRow> after = new ArrayList<>(before);
            after.removeIf(
                    grant ->
                            grant.grantId() == current.id()
                                    || (grant.subjectType()
                                                    == SubjectType.valueOf(
                                                            replacement.subjectType())
                                            && grant.subjectId() == replacement.subjectId()
                                            && grant.resourceId() == encodedResource
                                            && grant.action()
                                                    == PermissionGrant.Action.valueOf(
                                                            replacement.action())));
            after.add(toGrantRow(replacement, Long.MAX_VALUE));
            assertNoEscalation(directory, principal, before, after);
        }
        grantStore.revoke(id, expectedVersion, "replaced", principal.userId());
        AclGrantStore.AclGrantRecord created = grantStore.create(replacement, principal.userId());
        return ResponseEntity.ok().eTag(IfMatch.etag(created.version())).body(toRead(created));
    }

    @DeleteMapping("/grants/{id}")
    public ResponseEntity<GrantRead> revoke(@PathVariable long id, HttpServletRequest request) {
        long expectedVersion = IfMatch.requireVersion(request);
        // Re-revoking is an idempotent replay handled by the store; the gate still checks the
        // coordinates of the grant being touched.
        AclGrantStore.AclGrantRecord current = grantStore.get(id);
        Long revokeDept = requireTargetDepartment(current.subjectType(), current.subjectId());
        AdminPrincipal principal =
                ManagementScope.requireGrantOperator(
                        request,
                        delegations,
                        orgStore,
                        "acl:revoke",
                        current.resourceType(),
                        current.resourceId(),
                        revokeDept);
        AclGrantStore.AclGrantRecord revoked =
                grantStore.revoke(id, expectedVersion, "revoked via API", principal.userId());
        return ResponseEntity.ok().body(toRead(revoked));
    }

    private Long requireTargetDepartment(String subjectType, long subjectId) {
        try {
            return ManagementScope.subjectDepartment(orgStore, subjectType, subjectId);
        } catch (DomainException error) {
            // An unknown subject is out of every scope; the platform path reports 404 later.
            return null;
        }
    }

    @PostMapping("/preview")
    public PreviewResponse preview(
            @RequestBody PreviewRequest previewRequest, HttpServletRequest request) {
        ManagementScope.requirePlatformAdmin(request);
        if (previewRequest.subjectUserId() == null
                || previewRequest.platformId() == null
                || previewRequest.resourceIds() == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        Instant at = previewRequest.at() == null ? Instant.now() : previewRequest.at();
        List<AclDirectory.ResourceDecision> decisions =
                directory.preview(
                        previewRequest.subjectUserId(),
                        previewRequest.platformId(),
                        previewRequest.resourceIds(),
                        at);
        Instant nextBoundary =
                decisions.stream()
                        .map(AclDirectory.ResourceDecision::nextBoundaryAt)
                        .filter(boundary -> boundary != null)
                        .min(Instant::compareTo)
                        .orElse(null);
        return new PreviewResponse(
                decisions.stream()
                        .map(
                                decision ->
                                        new PreviewDecision(
                                                decision.resourceId(),
                                                decision.meta(),
                                                decision.read(),
                                                decision.sourceGrantIds()))
                        .toList(),
                Instant.now(),
                nextBoundary);
    }

    private static List<GrantRow> callerGrantsOrNull(
            AclDirectory directory, AdminPrincipal principal) {
        SubjectScope scope = ManagementScope.callerScopeOrNull(directory, principal);
        if (scope == null) {
            return null;
        }
        return directory.activeGrants(principal.userId());
    }

    private static void assertNoEscalation(
            AclDirectory directory,
            AdminPrincipal principal,
            List<GrantRow> before,
            List<GrantRow> after) {
        SubjectScope scope = ManagementScope.callerScopeOrNull(directory, principal);
        if (scope == null) {
            return;
        }
        List<ResourceNode> tree = directory.resourceTree();
        // Platform admins sit above the whole ceiling; delegation-less admin scope is binary.
        SelfEscalationGuard.assertNoEscalation(
                scope, before, after, tree, true, true, Instant.now());
    }

    private static AclGrantStore.AclGrantRecord toDraft(GrantWrite write) {
        if (write.subjectType() == null
                || write.subjectId() == null
                || write.resourceType() == null
                || write.resourceId() == null
                || write.resourceGeneration() == null
                || write.action() == null
                || write.effect() == null
                || write.includeDescendants() == null
                || write.validFrom() == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        return new AclGrantStore.AclGrantRecord(
                0,
                write.subjectType(),
                write.subjectId(),
                write.resourceType(),
                write.resourceId(),
                write.resourceGeneration(),
                write.action(),
                write.effect(),
                write.includeDescendants(),
                write.validFrom(),
                write.validTo(),
                "ACTIVE",
                write.reason() == null ? "" : write.reason(),
                1);
    }

    private static GrantRow toGrantRow(AclGrantStore.AclGrantRecord record, long grantId) {
        return new GrantRow(
                SubjectType.valueOf(record.subjectType()),
                record.subjectId(),
                ResourceIds.encode(
                        ResourceIds.Type.valueOf(record.resourceType()), record.resourceId()),
                PermissionGrant.Action.valueOf(record.action()),
                PermissionGrant.Effect.valueOf(record.effect()),
                record.includeDescendants(),
                record.validFrom(),
                record.validTo(),
                grantId);
    }

    private static GrantRead toRead(AclGrantStore.AclGrantRecord record) {
        return new GrantRead(
                record.id(),
                record.subjectType(),
                record.subjectId(),
                record.resourceType(),
                record.resourceId(),
                record.resourceGeneration(),
                record.action(),
                record.effect(),
                record.includeDescendants(),
                record.validFrom(),
                record.validTo(),
                record.status(),
                record.reason(),
                record.version());
    }
}
