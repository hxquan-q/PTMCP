package local.ptmcp.admin.me;

import jakarta.servlet.http.HttpServletRequest;

import local.ptmcp.admin.audit.AuditQueryService;
import local.ptmcp.admin.session.AdminSessionService;
import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

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

import java.time.Instant;
import java.util.List;

/** API-spec §6 personal portal. The user id always comes from the ADMIN session cookie. */
@RestController
@RequestMapping("/api/v1/me")
@ConditionalOnProperty("ptmcp.admin.datasource.url")
public class MeController {
    private final AdminSessionService sessions;
    private final MeService me;
    private final PersonalTokenService tokens;

    public MeController(AdminSessionService sessions, MeService me, PersonalTokenService tokens) {
        this.sessions = sessions;
        this.me = me;
        this.tokens = tokens;
    }

    public record TokenCreateRequest(
            String name, Long platformId, List<String> scopes, Instant expiresAt) {}

    @GetMapping("/access-context")
    public MeService.AccessContext accessContext(
            @RequestParam String level,
            @RequestParam(required = false) Long platformId,
            @RequestParam(required = false) Long datasourceId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer pageSize,
            HttpServletRequest request) {
        return me.accessContext(userId(request), level, platformId, datasourceId, cursor, pageSize);
    }

    @GetMapping("/dictionary")
    public MeService.ObjectPage dictionary(
            @RequestParam Long platformId,
            @RequestParam Long datasourceId,
            @RequestParam(required = false) String databaseName,
            @RequestParam(required = false) String schemaName,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer pageSize,
            HttpServletRequest request) {
        return me.dictionary(
                userId(request),
                platformId,
                datasourceId,
                databaseName,
                schemaName,
                keyword,
                cursor,
                pageSize);
    }

    @GetMapping("/dictionary/{objectId}")
    public MeService.ObjectDetail dictionaryDetail(
            @PathVariable long objectId,
            @RequestParam Long platformId,
            HttpServletRequest request) {
        return me.dictionaryDetail(userId(request), platformId, objectId);
    }

    @GetMapping("/tokens")
    public PersonalTokenService.TokenPage tokens(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer pageSize,
            HttpServletRequest request) {
        return tokens.list(userId(request), status, cursor, pageSize);
    }

    @PostMapping("/tokens")
    public ResponseEntity<PersonalTokenService.Created> createToken(
            @RequestBody TokenCreateRequest body, HttpServletRequest request) {
        if (body == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        return ResponseEntity.status(201)
                .body(
                        tokens.create(
                                userId(request),
                                sessions.requireSessionAuthVersion(request),
                                body.name(),
                                body.platformId(),
                                body.scopes(),
                                body.expiresAt()));
    }

    @DeleteMapping("/tokens/{id}")
    public PersonalTokenService.TokenView revokeToken(
            @PathVariable long id, HttpServletRequest request) {
        return tokens.revoke(userId(request), id);
    }

    @GetMapping("/history")
    public AuditQueryService.AuditPage history(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String tool,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String verdict,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer pageSize,
            HttpServletRequest request) {
        return me.history(userId(request), from, to, tool, result, verdict, cursor, pageSize);
    }

    @GetMapping("/history/{requestId}")
    public AuditQueryService.AuditDetail historyDetail(
            @PathVariable String requestId, HttpServletRequest request) {
        return me.historyDetail(userId(request), requestId);
    }

    @GetMapping("/mcp-config")
    public MeService.McpConfig mcpConfig(
            @RequestParam Long platformId,
            @RequestParam String clientType,
            HttpServletRequest request) {
        return me.mcpConfig(userId(request), platformId, clientType);
    }

    @GetMapping("/authorizations")
    public MeService.AuthorizationPage authorizations(
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer pageSize,
            HttpServletRequest request) {
        return me.authorizations(userId(request), cursor, pageSize);
    }

    @DeleteMapping("/authorizations/{familyId}")
    public MeService.AuthorizationRevoked revokeAuthorization(
            @PathVariable String familyId, HttpServletRequest request) {
        return me.revokeAuthorization(userId(request), familyId);
    }

    private long userId(HttpServletRequest request) {
        return sessions.requireSessionUserId(request);
    }
}
