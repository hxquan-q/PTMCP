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

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * /api/v1/admin/roles CRUD; response bodies are explicit DTOs (R<T> wrapper pending).
 *
 * <p>Roles are pure data-permission templates (S3: no capability codes; the delegation surface was
 * removed in S4), so every write stays platform-admin only via the binary management gate.
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

    public RoleAdminController(OrgStore orgStore, IdempotencyRecorder idempotency) {
        this.orgStore = orgStore;
        this.idempotency = idempotency;
    }

    /** S3 后角色 = 数据权限模板：请求与响应都只含定义字段，不再携带能力码。 */
    public record RoleWrite(String key, String name, Boolean enabled) {}

    public record RoleRead(long id, String key, String name, boolean enabled, long version) {}

    @AdminAction("ptmcp:role:list")
    @GetMapping
    public List<RoleRead> list(HttpServletRequest request) {
        ManagementScope.requirePlatformAdmin(request);
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
        ManagementScope.requirePlatformAdmin(request);
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
    /** S4: 目录读取面收敛到唯一平台管理员，委派读取路径已删除。 */
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
        return new RoleRead(row.id(), row.key(), row.name(), row.enabled(), row.version());
    }
}
