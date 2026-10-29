package local.ptmcp.admin.api;

import jakarta.servlet.http.HttpServletRequest;

import local.ptmcp.admin.platform.ClientAdminService;
import local.ptmcp.admin.web.StrictJson;
import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * /api/v1/admin/clients/{id}: detail, optimistic update and delete for one registered OAuth client.
 * Secret material never comes back; updates and deletes revoke the client's refresh families inside
 * the same transaction. Creation lives under /platforms/{id}/clients because the secret-once
 * response needs the owning platform context.
 */
@RestController
@ConditionalOnProperty("ptmcp.admin.datasource.url")
@RequestMapping("/api/v1/admin/clients")
public class ClientAdminController {
    private final ClientAdminService clients;

    public ClientAdminController(ClientAdminService clients) {
        this.clients = clients;
    }

    public record ClientWritePayload(
            String name,
            String type,
            List<String> authMethods,
            List<String> redirectUris,
            List<String> allowedScopes,
            String jwksUri,
            String status) {}

    @AdminAction("ptmcp:client:read")
    @GetMapping("/{id}")
    public ClientAdminService.ClientView get(@PathVariable String id, HttpServletRequest request) {
        ManagementScope.requirePlatformAdmin(request);
        return clients.get(id);
    }

    @AdminAction("ptmcp:client:update")
    @PutMapping("/{id}")
    public ResponseEntity<ClientAdminService.ClientView> update(
            @PathVariable String id,
            @RequestBody(required = false) byte[] body,
            HttpServletRequest request) {
        long expectedVersion = IfMatch.requireVersion(request);
        AdminPrincipal principal = ManagementScope.requirePlatformAdminPrincipal(request);
        ClientWritePayload payload = StrictJson.parse(body, ClientWritePayload.class);
        ClientAdminService.ClientView updated =
                clients.update(
                        id,
                        expectedVersion,
                        toWrite(payload),
                        principal.userId(),
                        UUID.randomUUID().toString());
        return ResponseEntity.ok()
                .eTag(IfMatch.etag(Long.parseLong(updated.version())))
                .body(updated);
    }

    @AdminAction("ptmcp:client:delete")
    @DeleteMapping("/{id}")
    public ResponseEntity<ClientAdminService.ClientView> delete(
            @PathVariable String id, HttpServletRequest request) {
        long expectedVersion = IfMatch.requireVersion(request);
        AdminPrincipal principal = ManagementScope.requirePlatformAdminPrincipal(request);
        ClientAdminService.ClientView deleted =
                clients.delete(
                        id, expectedVersion, principal.userId(), UUID.randomUUID().toString());
        return ResponseEntity.ok()
                .eTag(IfMatch.etag(Long.parseLong(deleted.version())))
                .body(deleted);
    }

    private static ClientAdminService.ClientWrite toWrite(ClientWritePayload payload) {
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
}
