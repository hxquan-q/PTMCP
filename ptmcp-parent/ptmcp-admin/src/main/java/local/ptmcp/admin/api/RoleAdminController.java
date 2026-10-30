package local.ptmcp.admin.api;

import jakarta.servlet.http.HttpServletRequest;

import local.ptmcp.admin.web.StrictJson;
import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.persistence.org.DelegationScopeResolver;
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

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/**
 * /api/v1/admin/roles CRUD; response bodies are explicit DTOs (R<T> wrapper pending).
 *
 * <p>Roles are shared across management scopes (PRD: a delegated admin must not modify them), so
 * writes stay platform-admin only. Delegated admins keep read access to the catalog — assigning
 * existing roles to their subtree users needs it — gated on holding any effective delegation.
 */
@RestController
@ConditionalOnProperty("ptmcp.admin.datasource.url")
@RequestMapping("/api/v1/admin/roles")
public class RoleAdminController {
    // The recorded MEDIUMBLOB can exceed the request cap when the response adds id/version.
    private static final int MAX_RECORDED_RESPONSE_BYTES = 16 * 1024 * 1024 - 1;
    private static final JsonMapper RECORDED_JSON =
            JsonMapper.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .build();

    private final OrgStore orgStore;
    private final IdempotencyRecorder idempotency;
    private final DelegationScopeResolver scopeResolver;

    public RoleAdminController(
            OrgStore orgStore,
            IdempotencyRecorder idempotency,
            DelegationScopeResolver scopeResolver) {
        this.orgStore = orgStore;
        this.idempotency = idempotency;
        this.scopeResolver = scopeResolver;
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

    @AdminAction("ptmcp:role:list")
    @GetMapping
    public List<RoleRead> list(HttpServletRequest request) {
        requireCatalogAccess(request);
        return orgStore.listRoles().stream().map(RoleAdminController::toRead).toList();
    }

    @AdminAction("ptmcp:role:create")
    @PostMapping
    public ResponseEntity<byte[]> create(
            @RequestBody(required = false) byte[] body, HttpServletRequest request) {
        ManagementScope.requirePlatformAdmin(request);
        RoleWrite write = StrictJson.parse(body, RoleWrite.class);
        if (write.key() == null || write.name() == null || write.enabled() == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        ResponseEntity<byte[]> response =
                IdempotentWrites.execute(
                        idempotency,
                        actorId(request),
                        "roles.create",
                        request.getHeader("Idempotency-Key"),
                        body,
                        () -> {
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
                            return ResponseEntity.created(
                                            URI.create("/api/v1/admin/roles/" + created.id()))
                                    .body(created);
                        });
        return withCreatedLocation(response);
    }

    @AdminAction("ptmcp:role:read")
    @GetMapping("/{id}")
    public RoleRead get(@PathVariable long id, HttpServletRequest request) {
        requireCatalogAccess(request);
        return toRead(orgStore.getRole(id));
    }

    @AdminAction("ptmcp:role:update")
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

    @AdminAction("ptmcp:role:delete")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id, HttpServletRequest request) {
        long expectedVersion = IfMatch.requireVersion(request);
        ManagementScope.requirePlatformAdmin(request);
        orgStore.deleteRole(id, expectedVersion, actorId(request));
        return ResponseEntity.noContent().build();
    }

    /** Catalog read: platform admins, or delegated admins with any effective delegation. */
    private void requireCatalogAccess(HttpServletRequest request) {
        AdminPrincipal principal = AdminPrincipal.require(request);
        if (principal.platformAdmin()) {
            return;
        }
        if (scopeResolver.effectiveDelegations(principal.userId(), Instant.now()).isEmpty()) {
            throw new DomainException(ErrorCode.MANAGEMENT_SCOPE_DENIED);
        }
    }

    private static long actorId(HttpServletRequest request) {
        return AdminPrincipal.require(request).userId();
    }

    private static ResponseEntity<byte[]> withCreatedLocation(ResponseEntity<byte[]> response) {
        if (response.getHeaders().getLocation() != null) {
            return response;
        }
        // Replays retain status/body only; use the recorded id even if the role has since changed.
        byte[] body = response.getBody();
        if (body == null || body.length == 0 || body.length > MAX_RECORDED_RESPONSE_BYTES) {
            throw new DomainException(ErrorCode.OPERATION_UNKNOWN);
        }
        RoleRead recorded;
        try {
            String json =
                    StandardCharsets.UTF_8
                            .newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(body))
                            .toString();
            recorded = RECORDED_JSON.readValue(json, RoleRead.class);
        } catch (JacksonException | CharacterCodingException failure) {
            throw new DomainException(ErrorCode.OPERATION_UNKNOWN);
        }
        if (recorded == null || recorded.id() <= 0) {
            throw new DomainException(ErrorCode.OPERATION_UNKNOWN);
        }
        return ResponseEntity.status(response.getStatusCode())
                .headers(response.getHeaders())
                .location(URI.create("/api/v1/admin/roles/" + recorded.id()))
                .body(response.getBody());
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
