package local.ptmcp.admin.api;

import jakarta.servlet.http.HttpServletRequest;

import local.ptmcp.admin.session.AdminSessionService;
import local.ptmcp.admin.web.RequestIdFilter;
import local.ptmcp.common.authz.ResourceNode;
import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.org.AclDirectory;
import local.ptmcp.persistence.org.AclGrantStore;
import local.ptmcp.persistence.org.ResourceIds;

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
import java.util.List;

/**
 * /api/v1/admin/acl/grants CRUD and /acl/preview. Every mutating grant operation first proves it
 * does not raise the caller's own effective META/READ at current or future grant boundaries
 * (self-privilege-escalation, 403), on the same connection as its atomic write. The preview judges
 * only — it never borrows a business connection.
 */
@RestController
@ConditionalOnProperty("ptmcp.admin.datasource.url")
@RequestMapping("/api/v1/admin/acl")
public class AclGrantAdminController {
    private final AclGrantStore grantStore;
    private final AclDirectory directory;
    private final AclMutationGuard mutations;
    private final AdminSessionService sessions;

    public AclGrantAdminController(
            AclGrantStore grantStore, AclDirectory directory, AdminSessionService sessions) {
        this.grantStore = grantStore;
        this.directory = directory;
        this.mutations = new AclMutationGuard(directory);
        this.sessions = sessions;
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

    public record EffectiveRow(
            String resourceType,
            long resourceId,
            boolean meta,
            boolean read,
            List<Long> sourceGrantIds,
            List<String> reasonCodes) {}

    public record EffectivePage(List<EffectiveRow> items, String nextCursor) {}

    /**
     * GET /acl/effective: the subject's current decisions over the resource tree, one stable
     * id-ordered page at a time, bounded by the same platform ceiling as execution preview. The
     * datasource filter selects the datasource node itself plus its namespaces and objects. The
     * page window is derived from the tree, so a page always reflects the current tree state.
     */
    @AdminAction("ptmcp:acl:effective")
    @GetMapping("/effective")
    public EffectivePage effective(
            @RequestParam Long userId,
            @RequestParam Long platformId,
            @RequestParam(required = false) Long datasourceId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer pageSize,
            HttpServletRequest request) {
        ManagementScope.requirePlatformAdmin(request);
        int limit = pageSize == null ? 20 : pageSize;
        if (limit < 1 || limit > 100) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        long afterId = 0;
        if (cursor != null && !cursor.isBlank()) {
            try {
                afterId = Long.parseLong(cursor);
            } catch (NumberFormatException failure) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
            if (afterId < 0) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
        }
        List<Long> pageIds =
                effectiveResourceIds(directory.resourceTree(), datasourceId, afterId, limit);
        List<AclDirectory.ResourceDecision> decisions =
                pageIds.isEmpty()
                        ? List.of()
                        : directory.preview(userId, platformId, pageIds, Instant.now());
        List<EffectiveRow> items =
                decisions.stream()
                        .map(
                                decision -> {
                                    ResourceIds.Type type =
                                            ResourceIds.typeOf(decision.resourceId());
                                    return new EffectiveRow(
                                            type.name(),
                                            decision.resourceId(),
                                            decision.meta(),
                                            decision.read(),
                                            decision.sourceGrantIds(),
                                            reasonCodes(decision));
                                })
                        .toList();
        String nextCursor = pageIds.size() == limit ? String.valueOf(pageIds.getLast()) : null;
        return new EffectivePage(items, nextCursor);
    }

    /** Stable id-ordered window of the tree; an unknown datasource yields an empty page. */
    private static List<Long> effectiveResourceIds(
            List<ResourceNode> tree, Long datasourceId, long afterId, int limit) {
        java.util.Set<Long> selected = new java.util.LinkedHashSet<>();
        if (datasourceId == null) {
            for (ResourceNode node : tree) {
                selected.add(node.id());
            }
        } else {
            long rootId = ResourceIds.encode(ResourceIds.Type.DATASOURCE, datasourceId);
            java.util.Set<Long> namespaceIds = new java.util.HashSet<>();
            for (ResourceNode node : tree) {
                if (node.id() == rootId) {
                    selected.add(node.id());
                } else if (ResourceIds.typeOf(node.id()) == ResourceIds.Type.NAMESPACE
                        && rootId == node.parentId()) {
                    namespaceIds.add(node.id());
                    selected.add(node.id());
                }
            }
            for (ResourceNode node : tree) {
                if (ResourceIds.typeOf(node.id()) == ResourceIds.Type.OBJECT
                        && node.parentId() != null
                        && namespaceIds.contains(node.parentId())) {
                    selected.add(node.id());
                }
            }
            if (selected.isEmpty()) {
                return List.of();
            }
        }
        return selected.stream().sorted().filter(id -> id > afterId).limit(limit).toList();
    }

    /** Closed-world explanations: why each decision holds, without inventing grant ids. */
    private static List<String> reasonCodes(AclDirectory.ResourceDecision decision) {
        if (decision.resourceId() <= 0) {
            return List.of("UNKNOWN_RESOURCE");
        }
        if (decision.meta() && decision.read()) {
            return List.of("GRANT_META", "GRANT_READ");
        }
        if (decision.meta()) {
            return List.of("GRANT_META");
        }
        if (decision.read()) {
            return List.of("GRANT_READ");
        }
        return List.of("NO_EFFECTIVE_GRANT");
    }

    @AdminAction("ptmcp:acl:list")
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

    @AdminAction("ptmcp:acl:create")
    @PostMapping("/grants")
    public ResponseEntity<GrantRead> create(
            @RequestBody GrantWrite write, HttpServletRequest request) {
        AdminPrincipal principal = AdminPrincipal.require(request);
        AclGrantStore.AclGrantRecord draft = toDraft(write);
        AclGrantStore.AclGrantRecord created =
                grantStore.create(
                        draft,
                        principal.userId(),
                        RequestIdFilter.requestId(request),
                        mutations.policy(principal.userId(), expectedAuthVersion(request)));
        return ResponseEntity.created(
                        java.net.URI.create("/api/v1/admin/acl/grants/" + created.id()))
                .body(toRead(created));
    }

    @AdminAction("ptmcp:acl:read")
    @GetMapping("/grants/{id}")
    public GrantRead get(@PathVariable long id, HttpServletRequest request) {
        ManagementScope.requirePlatformAdmin(request);
        return toRead(grantStore.get(id));
    }

    @AdminAction("ptmcp:acl:update")
    @PutMapping("/grants/{id}")
    public ResponseEntity<GrantRead> update(
            @PathVariable long id, @RequestBody GrantWrite write, HttpServletRequest request) {
        long expectedVersion = IfMatch.requireVersion(request);
        AdminPrincipal principal = AdminPrincipal.require(request);
        AclGrantStore.AclGrantRecord updated =
                grantStore.update(
                        id,
                        expectedVersion,
                        toDraft(write),
                        principal.userId(),
                        RequestIdFilter.requestId(request),
                        mutations.policy(principal.userId(), expectedAuthVersion(request)));
        return ResponseEntity.ok().eTag(IfMatch.etag(updated.version())).body(toRead(updated));
    }

    @AdminAction("ptmcp:acl:delete")
    @DeleteMapping("/grants/{id}")
    public ResponseEntity<GrantRead> revoke(@PathVariable long id, HttpServletRequest request) {
        long expectedVersion = IfMatch.requireVersion(request);
        AdminPrincipal principal = AdminPrincipal.require(request);
        AclGrantStore.AclGrantRecord revoked =
                grantStore.revoke(
                        id,
                        expectedVersion,
                        "revoked via API",
                        principal.userId(),
                        RequestIdFilter.requestId(request),
                        mutations.policy(principal.userId(), expectedAuthVersion(request)));
        return ResponseEntity.ok().body(toRead(revoked));
    }

    @AdminAction("ptmcp:acl:preview")
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

    private Long expectedAuthVersion(HttpServletRequest request) {
        return Boolean.TRUE.equals(
                        request.getAttribute(AdminSessionService.SESSION_AUTHENTICATED_ATTRIBUTE))
                ? sessions.requireSessionAuthVersion(request)
                : null;
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
