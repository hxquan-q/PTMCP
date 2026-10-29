package local.ptmcp.admin.api;

import jakarta.servlet.http.HttpServletRequest;

import local.ptmcp.admin.platform.ClientAdminService;
import local.ptmcp.admin.platform.PlatformAdminService;
import local.ptmcp.admin.platform.PlatformDirectoryService;
import local.ptmcp.admin.web.StrictJson;
import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
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
import java.util.List;
import java.util.UUID;

/**
 * /api/v1/admin/platforms: the directory list plus the platform-console CRUD. Proof configuration
 * is sealed by the deployment cipher and never readable again; mutations are optimistic (If-Match)
 * and idempotent (Idempotency-Key on POST) and each writes one admin_operation_log row. Scope and
 * client management stay in their own slices.
 */
@RestController
@ConditionalOnProperty("ptmcp.admin.datasource.url")
@RequestMapping("/api/v1/admin/platforms")
public class PlatformAdminController {
    private final PlatformDirectoryService platforms;
    private final PlatformAdminService mutations;
    private final ClientAdminService clients;
    private final IdempotencyRecorder idempotency;

    public PlatformAdminController(
            PlatformDirectoryService platforms,
            PlatformAdminService mutations,
            ClientAdminService clients,
            IdempotencyRecorder idempotency) {
        this.platforms = platforms;
        this.mutations = mutations;
        this.clients = clients;
        this.idempotency = idempotency;
    }

    public record PlatformWrite(
            String code,
            String name,
            Integer tier,
            String catalogMode,
            String proofMode,
            String status,
            ProofConfig proofConfig) {}

    public record ProofConfig(String certificateFingerprint, String proxyNetwork, String secret) {}

    public record PageView(
            java.util.List<PlatformDirectoryService.PlatformView> items, String nextCursor) {}

    @AdminAction("ptmcp:platform:list")
    @GetMapping
    public ResponseEntity<PageView> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer pageSize,
            HttpServletRequest request) {
        ManagementScope.requirePlatformAdmin(request);
        PlatformDirectoryService.Page page =
                platforms.list(keyword, status, parseCursor(cursor), pageSize);
        return ResponseEntity.ok(new PageView(page.items(), page.nextCursor()));
    }

    @AdminAction("ptmcp:platform:read")
    @GetMapping("/{id}")
    public ResponseEntity<PlatformDirectoryService.PlatformView> get(
            @PathVariable long id, HttpServletRequest request) {
        ManagementScope.requirePlatformAdmin(request);
        PlatformDirectoryService.PlatformView view = toView(mutations.get(id));
        return ResponseEntity.ok().eTag(IfMatch.etag(Long.parseLong(view.version()))).body(view);
    }

    @AdminAction("ptmcp:platform:create")
    @PostMapping
    public ResponseEntity<byte[]> create(
            @RequestBody(required = false) byte[] body, HttpServletRequest request) {
        AdminPrincipal principal = ManagementScope.requirePlatformAdminPrincipal(request);
        PlatformWrite write = StrictJson.parse(body, PlatformWrite.class);
        try {
            return doCreate(principal, write, body, request);
        } catch (RuntimeException error) {
            error.printStackTrace();
            throw error;
        }
    }

    private ResponseEntity<byte[]> doCreate(
            AdminPrincipal principal,
            PlatformWrite write,
            byte[] body,
            HttpServletRequest request) {
        return IdempotentWrites.execute(
                idempotency,
                principal.userId(),
                "platforms.create",
                idempotencyKey(request),
                body,
                () -> {
                    PlatformDirectoryService.PlatformView created =
                            toView(
                                    mutations.create(
                                            toServiceWrite(write),
                                            principal.userId(),
                                            UUID.randomUUID().toString()));
                    return ResponseEntity.created(
                                    URI.create("/api/v1/admin/platforms/" + created.id()))
                            .eTag(IfMatch.etag(Long.parseLong(created.version())))
                            .body(created);
                });
    }

    @AdminAction("ptmcp:platform:update")
    @PutMapping("/{id}")
    public ResponseEntity<PlatformDirectoryService.PlatformView> update(
            @PathVariable long id,
            @RequestBody(required = false) byte[] body,
            HttpServletRequest request) {
        long expectedVersion = IfMatch.requireVersion(request);
        AdminPrincipal principal = ManagementScope.requirePlatformAdminPrincipal(request);
        PlatformWrite write = StrictJson.parse(body, PlatformWrite.class);
        PlatformDirectoryService.PlatformView updated =
                toView(
                        mutations.update(
                                id,
                                expectedVersion,
                                toServiceWrite(write),
                                principal.userId(),
                                UUID.randomUUID().toString()));
        return ResponseEntity.ok()
                .eTag(IfMatch.etag(Long.parseLong(updated.version())))
                .body(updated);
    }

    @AdminAction("ptmcp:platform:delete")
    @DeleteMapping("/{id}")
    public ResponseEntity<PlatformDirectoryService.PlatformView> delete(
            @PathVariable long id, HttpServletRequest request) {
        long expectedVersion = IfMatch.requireVersion(request);
        AdminPrincipal principal = ManagementScope.requirePlatformAdminPrincipal(request);
        PlatformDirectoryService.PlatformView deleted =
                toView(
                        mutations.delete(
                                id,
                                expectedVersion,
                                principal.userId(),
                                UUID.randomUUID().toString()));
        return ResponseEntity.ok()
                .eTag(IfMatch.etag(Long.parseLong(deleted.version())))
                .body(deleted);
    }

    public record ClientWritePayload(
            String name,
            String type,
            List<String> authMethods,
            List<String> redirectUris,
            List<String> allowedScopes,
            String jwksUri,
            String status) {}

    /** POST /{id}/clients: registers a client; CONFIDENTIAL secrets appear once, never again. */
    @AdminAction("ptmcp:client:create")
    @PostMapping("/{id}/clients")
    public ResponseEntity<byte[]> createClient(
            @PathVariable long id,
            @RequestBody(required = false) byte[] body,
            HttpServletRequest request) {
        AdminPrincipal principal = ManagementScope.requirePlatformAdminPrincipal(request);
        ClientWritePayload payload = StrictJson.parse(body, ClientWritePayload.class);
        return IdempotentWrites.execute(
                idempotency,
                principal.userId(),
                "clients.create:" + id,
                idempotencyKey(request),
                body,
                () -> {
                    ClientAdminService.CreatedClient created =
                            clients.create(
                                    id,
                                    toClientWrite(payload),
                                    principal.userId(),
                                    UUID.randomUUID().toString());
                    return ResponseEntity.status(201)
                            .location(URI.create("/api/v1/admin/clients/" + created.client().id()))
                            .body(created);
                });
    }

    @AdminAction("ptmcp:client:list")
    @GetMapping("/{id}/clients")
    public java.util.Map<String, Object> listClients(
            @PathVariable long id,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer pageSize,
            HttpServletRequest request) {
        ManagementScope.requirePlatformAdmin(request);
        List<ClientAdminService.ClientView> items =
                clients.list(id, type, status, cursor, pageSize);
        return java.util.Map.of("items", List.copyOf(items));
    }

    private static ClientAdminService.ClientWrite toClientWrite(ClientWritePayload payload) {
        if (payload == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        return new ClientAdminService.ClientWrite(
                payload.name(),
                payload.type(),
                payload.authMethods(),
                payload.redirectUris(),
                payload.allowedScopes(),
                payload.jwksUri(),
                payload.status());
    }

    /** GET /{id}/scopes: the full scope set plus the version the write is keyed on. */
    @AdminAction("ptmcp:platform:read")
    @GetMapping("/{id}/scopes")
    public PlatformAdminService.ScopeRead getScopes(
            @PathVariable long id, HttpServletRequest request) {
        ManagementScope.requirePlatformAdmin(request);
        return mutations.getScope(id);
    }

    /** PUT /{id}/scopes: full-set replacement under If-Match; bumps scope + security versions. */
    @AdminAction("ptmcp:platform:scope")
    @PutMapping("/{id}/scopes")
    public ResponseEntity<PlatformAdminService.ScopeRead> putScopes(
            @PathVariable long id,
            @RequestBody(required = false) byte[] body,
            HttpServletRequest request) {
        long expectedVersion = IfMatch.requireVersion(request);
        AdminPrincipal principal = ManagementScope.requirePlatformAdminPrincipal(request);
        ScopeWrite write = StrictJson.parse(body, ScopeWrite.class);
        List<PlatformAdminService.ScopeRule> rules =
                write.items() == null
                        ? List.of()
                        : write.items().stream()
                                .map(
                                        item ->
                                                new PlatformAdminService.ScopeRule(
                                                        item.resourceType(),
                                                        item.resourceId(),
                                                        item.resourceGeneration(),
                                                        item.action(),
                                                        item.includeDescendants()))
                                .toList();
        PlatformAdminService.ScopeRead saved =
                mutations.saveScope(
                        id,
                        expectedVersion,
                        rules,
                        principal.userId(),
                        UUID.randomUUID().toString());
        return ResponseEntity.ok().eTag(IfMatch.etag(Long.parseLong(saved.version()))).body(saved);
    }

    public record ScopeWrite(java.util.List<ScopeItem> items) {}

    public record ScopeItem(
            String resourceType,
            Long resourceId,
            Long resourceGeneration,
            String action,
            Boolean includeDescendants) {}

    private static PlatformAdminService.Write toServiceWrite(PlatformWrite write) {
        if (write == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        PlatformAdminService.ProofConfig proofConfig =
                write.proofConfig() == null
                        ? null
                        : new PlatformAdminService.ProofConfig(
                                write.proofConfig().certificateFingerprint(),
                                write.proofConfig().proxyNetwork(),
                                write.proofConfig().secret());
        return new PlatformAdminService.Write(
                write.code(),
                write.name(),
                write.tier(),
                write.catalogMode(),
                write.proofMode(),
                write.status(),
                proofConfig);
    }

    private static PlatformDirectoryService.PlatformView toView(
            PlatformAdminService.PlatformView view) {
        return new PlatformDirectoryService.PlatformView(
                view.id(),
                view.code(),
                view.name(),
                view.tier(),
                view.status(),
                view.catalogMode(),
                view.proofMode(),
                view.proofConfigured(),
                view.scopeVersion(),
                view.securityVersion(),
                view.version());
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
