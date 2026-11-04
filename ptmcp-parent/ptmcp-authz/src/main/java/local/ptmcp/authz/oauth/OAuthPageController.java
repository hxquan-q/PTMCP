package local.ptmcp.authz.oauth;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pages hosted on the AS origin. The optional React bundle reads its data from the JSON context
 * endpoints; the server-rendered pages remain as a fallback. Every value that reaches a page is
 * either server-verified or escaped, and no response is cacheable.
 */
@Controller
@ConditionalOnProperty(prefix = "ptmcp.authz.oauth", name = "enabled", havingValue = "true")
public class OAuthPageController {
    private static final Logger LOG = LoggerFactory.getLogger(OAuthPageController.class);
    static final MediaType HTML = MediaType.parseMediaType("text/html;charset=UTF-8");
    static final String CONTENT_SECURITY_POLICY =
            "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; "
                    + "frame-ancestors 'none'; base-uri 'none'";
    static final String REACT_CONTENT_SECURITY_POLICY =
            "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                    + "img-src 'self' data:; font-src 'self'; connect-src 'self'; media-src 'self'; "
                    + "form-action 'self'; frame-ancestors 'none'; base-uri 'none'";

    private static final String SCOPE_CATALOG = "mcp:catalog";
    private static final String SCOPE_SCHEMA = "mcp:schema";
    private static final String SCOPE_QUERY = "mcp:query";

    private final PtmcpRegisteredClientRepository clients;
    private final OAuth2AuthorizationService authorizations;
    private final OAuth2AuthorizationConsentService consents;
    private final AuthorizationServerSettings settings;
    private final OAuthPages pages;

    public OAuthPageController(
            PtmcpRegisteredClientRepository clients,
            OAuth2AuthorizationService authorizations,
            OAuth2AuthorizationConsentService consents,
            AuthorizationServerSettings settings,
            OAuthPages pages) {
        this.clients = clients;
        this.authorizations = authorizations;
        this.consents = consents;
        this.settings = settings;
        this.pages = pages;
    }

    @GetMapping(OAuthSecurityConfiguration.LOGIN_PAGE)
    public ResponseEntity<String> login(
            HttpServletRequest request,
            @RequestParam(name = "error", required = false) String error,
            @RequestParam(name = "logout", required = false) String logout) {
        if (pages.configured()) {
            return reactPage(pages.loginHtml());
        }
        String notice =
                error != null
                        ? "<p class=\"notice error\">账号或密码不正确，或账号已停用。</p>"
                        : logout != null ? "<p class=\"notice\">已安全退出。</p>" : "";
        String body =
                "<h1>登录 PTMCP</h1>"
                        + notice
                        + "<form method=\"post\" action=\""
                        + OAuthSecurityConfiguration.LOGIN_PAGE
                        + "\">"
                        + "<label>账号<input type=\"text\" name=\"username\" "
                        + "autocomplete=\"username\" maxlength=\"64\" required autofocus></label>"
                        + "<label>密码<input type=\"password\" name=\"password\" "
                        + "autocomplete=\"current-password\" required></label>"
                        + "<input type=\"hidden\" name=\"_csrf\" value=\""
                        + escape(csrfToken(request))
                        + "\">"
                        + "<button type=\"submit\">登录</button>"
                        + "</form>";
        return html(HttpStatus.OK, "登录 PTMCP", body);
    }

    @GetMapping(OAuthSecurityConfiguration.CONSENT_PAGE)
    public ResponseEntity<String> consent(
            HttpServletRequest request,
            Authentication authentication,
            @RequestParam(name = "client_id", required = false) String clientId,
            @RequestParam(name = "scope", required = false) String scope,
            @RequestParam(name = "state", required = false) String state) {
        if (pages.configured()) {
            // The shell carries no request data; the context endpoint validates and the page fails
            // closed when it cannot show an exact, server-verified callback target.
            return reactPage(pages.consentHtml());
        }
        ConsentLookup lookup = loadPendingConsent(clientId, state, authentication);
        if (lookup == null) {
            return errorPage("授权请求无效或已过期，请从客户端重新发起授权。");
        }
        RegisteredClient client = lookup.client();
        Set<String> requested = requestedScopes(scope, client, lookup.request().getScopes());
        if (requested == null) {
            return errorPage("授权请求包含未注册的权限范围。");
        }
        String redirectUri = lookup.request().getRedirectUri();
        if (!StringUtils.hasText(redirectUri) || !client.getRedirectUris().contains(redirectUri)) {
            return errorPage("授权请求的回调地址无效。");
        }
        Set<String> granted = grantedScopes(client, authentication, requested);

        String action = escape(settings.getAuthorizationEndpoint());
        String hidden =
                "<input type=\"hidden\" name=\"client_id\" value=\""
                        + escape(clientId)
                        + "\">"
                        + "<input type=\"hidden\" name=\"state\" value=\""
                        + escape(state)
                        + "\">"
                        + "<input type=\"hidden\" name=\"_csrf\" value=\""
                        + escape(csrfToken(request))
                        + "\">";
        StringBuilder boxes = new StringBuilder();
        for (String value : requested) {
            boolean alreadyGranted = granted.contains(value);
            boxes.append("<label class=\"scope\"><input type=\"checkbox\" name=\"scope\" value=\"")
                    .append(escape(value))
                    .append("\" checked")
                    .append(alreadyGranted ? " disabled" : "")
                    .append("> <span>")
                    .append(escape(value))
                    .append(alreadyGranted ? "（已授权）" : "")
                    .append("</span></label>");
        }
        String allow =
                requested.isEmpty()
                        ? "<p>本次请求未包含可授予的权限，只能拒绝。</p>"
                        : "<form method=\"post\" action=\""
                                + action
                                + "\">"
                                + hidden
                                + boxes
                                + "<button type=\"submit\">允许</button>"
                                + "</form>";
        String body =
                "<h1>授权确认</h1>"
                        + "<p>应用 <strong>"
                        + escape(client.getClientName())
                        + "</strong> 请求访问以下权限：</p>"
                        + allow
                        + "<form method=\"post\" action=\""
                        + escape(OAuthSecurityConfiguration.CONSENT_DENY)
                        + "\">"
                        + hidden
                        + "<button type=\"submit\" class=\"secondary\">拒绝</button>"
                        + "</form>";
        return html(HttpStatus.OK, "授权确认", body);
    }

    /**
     * Standard {@code access_denied} denial. SAS only denies when its consent builder ends up with
     * no authorities, which never happens while a previous consent exists; posting an empty scope
     * set would therefore issue a code with the old grants. This endpoint removes only the pending
     * authorization: a denial refuses this request, it does not revoke grants the user already
     * approved (those stay usable through their existing tokens, and the page says so).
     */
    @PostMapping(OAuthSecurityConfiguration.CONSENT_DENY)
    public ResponseEntity<?> denyConsent(
            Authentication authentication,
            @RequestParam(name = "client_id", required = false) String clientId,
            @RequestParam(name = "state", required = false) String state) {
        ConsentLookup lookup = loadPendingConsent(clientId, state, authentication);
        if (lookup == null) {
            return errorPage("授权请求无效或已过期，请从客户端重新发起授权。");
        }
        String redirectUri = lookup.request().getRedirectUri();
        if (!StringUtils.hasText(redirectUri)
                || !lookup.client().getRedirectUris().contains(redirectUri)) {
            return errorPage("授权请求的回调地址无效。");
        }
        authorizations.remove(lookup.authorization());
        UriComponentsBuilder location =
                UriComponentsBuilder.fromUriString(redirectUri)
                        .queryParam(OAuth2ParameterNames.ERROR, OAuth2ErrorCodes.ACCESS_DENIED);
        if (StringUtils.hasText(lookup.request().getState())) {
            location.queryParam(
                    OAuth2ParameterNames.STATE,
                    UriUtils.encode(lookup.request().getState(), StandardCharsets.UTF_8));
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(location.build(true).toUriString()))
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header("X-Frame-Options", "DENY")
                .header("Referrer-Policy", "no-referrer")
                .build();
    }

    @GetMapping(OAuthSecurityConfiguration.LOGIN_CONTEXT)
    public ResponseEntity<Map<String, Object>> loginContext(
            HttpServletRequest request,
            @RequestParam(name = "error", required = false) String error,
            @RequestParam(name = "logout", required = false) String logout) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("csrfToken", csrfToken(request));
        body.put("failed", error != null);
        body.put("loggedOut", logout != null);
        return json(HttpStatus.OK, body);
    }

    @GetMapping(OAuthSecurityConfiguration.CONSENT_CONTEXT)
    public ResponseEntity<Map<String, Object>> consentContext(
            HttpServletRequest request,
            Authentication authentication,
            @RequestParam(name = "client_id", required = false) String clientId,
            @RequestParam(name = "scope", required = false) String scope,
            @RequestParam(name = "state", required = false) String state) {
        // SAS generates its own consent state and binds the pending authorization to the signed-in
        // principal. The page may only show what that stored request already contains; the client
        // state from the query string is never trusted.
        ConsentLookup lookup = loadPendingConsent(clientId, state, authentication);
        if (lookup == null) {
            return securityError("no pending authorization for this request");
        }
        RegisteredClient client = lookup.client();
        Set<String> requestedValues = requestedScopes(scope, client, lookup.request().getScopes());
        if (requestedValues == null) {
            return securityError("unregistered scope");
        }
        String redirectUri = lookup.request().getRedirectUri();
        if (!StringUtils.hasText(redirectUri) || !client.getRedirectUris().contains(redirectUri)) {
            return securityError("no validated redirect target");
        }
        List<Map<String, String>> requested = new ArrayList<>();
        for (String value : requestedValues) {
            requested.add(scopeView(value));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("csrfToken", csrfToken(request));
        body.put("clientId", client.getClientId());
        body.put("clientName", client.getClientName());
        body.put(
                "platform",
                client.getClientSettings()
                        .getSetting(PtmcpRegisteredClientRepository.PLATFORM_NAME_SETTING));
        body.put("scopes", requested);
        body.put("grantedScopes", grantedScopes(client, authentication, requestedValues));
        body.put("redirectUri", redirectUri);
        body.put("state", state);
        body.put("authorizationEndpoint", settings.getAuthorizationEndpoint());
        body.put("denyEndpoint", OAuthSecurityConfiguration.CONSENT_DENY);
        body.put(
                "accessTokenTtlSeconds",
                client.getTokenSettings().getAccessTokenTimeToLive().toSeconds());
        body.put(
                "refreshTokenTtlSeconds",
                client.getTokenSettings().getRefreshTokenTimeToLive().toSeconds());
        body.put("refreshTokenRotation", !client.getTokenSettings().isReuseRefreshTokens());
        return json(HttpStatus.OK, body);
    }

    /**
     * Resolves the consent state to the in-flight authorization it names. The state is
     * SAS-generated and only meaningful while that authorization is pending (no code issued yet),
     * so a stale page, a replayed POST, another user's state, or another client's state all fail
     * closed.
     */
    private ConsentLookup loadPendingConsent(
            String clientId, String state, Authentication authentication) {
        if (!StringUtils.hasText(clientId) || !StringUtils.hasText(state)) {
            return null;
        }
        RegisteredClient client = clients.findByClientId(clientId);
        if (client == null) {
            return null;
        }
        OAuth2Authorization authorization =
                authorizations.findByToken(state, PtmcpOAuth2AuthorizationService.STATE_TOKEN_TYPE);
        if (authorization == null
                || !client.getId().equals(authorization.getRegisteredClientId())
                || authentication == null
                || !authentication.getName().equals(authorization.getPrincipalName())) {
            return null;
        }
        OAuth2AuthorizationRequest request =
                authorization.getAttribute(OAuth2AuthorizationRequest.class.getName());
        if (request == null) {
            return null;
        }
        return new ConsentLookup(client, authorization, request);
    }

    /**
     * Intersects the page query string with the scopes SAS stored on the authorization request.
     * Only stored-and-registered values are allowed; anything else rejects the whole request rather
     * than silently narrowing it.
     */
    private static Set<String> requestedScopes(
            String scope, RegisteredClient client, Set<String> storedScopes) {
        Set<String> requested = new LinkedHashSet<>();
        if (!StringUtils.hasText(scope)) {
            return requested;
        }
        for (String value : scope.trim().split("\\s+")) {
            if (!StringUtils.hasText(value) || !requested.add(value)) {
                continue;
            }
            if (!client.getScopes().contains(value) || !storedScopes.contains(value)) {
                return null;
            }
        }
        return requested;
    }

    /** Scopes the user already consented to that also belong to this request. */
    private Set<String> grantedScopes(
            RegisteredClient client, Authentication authentication, Set<String> requested) {
        Set<String> granted = new LinkedHashSet<>();
        OAuth2AuthorizationConsent consent =
                consents.findById(client.getId(), authentication.getName());
        if (consent == null) {
            return granted;
        }
        for (String value : consent.getScopes()) {
            if (requested.contains(value)) {
                granted.add(value);
            }
        }
        return granted;
    }

    private record ConsentLookup(
            RegisteredClient client,
            OAuth2Authorization authorization,
            OAuth2AuthorizationRequest request) {}

    private static Map<String, String> scopeView(String scope) {
        Map<String, String> view = new LinkedHashMap<>();
        view.put("value", scope);
        switch (scope) {
            case SCOPE_CATALOG -> {
                view.put("label", "目录");
                view.put("description", "查看授权平台内的数据源、数据库与表清单");
            }
            case SCOPE_SCHEMA -> {
                view.put("label", "结构");
                view.put("description", "读取授权对象的表结构与字段信息");
            }
            case SCOPE_QUERY -> {
                view.put("label", "查询");
                view.put("description", "在授权范围内执行只读 SQL 查询");
            }
            default -> {
                view.put("label", scope);
                view.put("description", "该客户端申请的自定义范围");
            }
        }
        return view;
    }

    private static ResponseEntity<Map<String, Object>> securityError(String reason) {
        String requestId = UUID.randomUUID().toString();
        LOG.warn("OAuth page request rejected: requestId={} reason={}", requestId, reason);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "invalid_authorization_request");
        body.put("message", "授权请求无效或已过期，请从客户端重新发起授权。");
        body.put("requestId", requestId);
        return json(HttpStatus.BAD_REQUEST, body);
    }

    private static ResponseEntity<Map<String, Object>> json(
            HttpStatus status, Map<String, Object> body) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header("X-Frame-Options", "DENY")
                .header("Referrer-Policy", "no-referrer")
                .body(body);
    }

    private static ResponseEntity<String> reactPage(String document) {
        return ResponseEntity.ok()
                .contentType(HTML)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header("X-Frame-Options", "DENY")
                .header("Referrer-Policy", "no-referrer")
                .header("Content-Security-Policy", REACT_CONTENT_SECURITY_POLICY)
                .body(document);
    }

    private static ResponseEntity<String> errorPage(String message) {
        return html(
                HttpStatus.BAD_REQUEST, "授权请求无效", "<h1>授权请求无效</h1><p>" + escape(message) + "</p>");
    }

    private static ResponseEntity<String> html(HttpStatus status, String title, String body) {
        String document =
                "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
                        + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                        + "<title>"
                        + escape(title)
                        + "</title><style>"
                        + "body{margin:0;font-family:system-ui,-apple-system,'Segoe UI',sans-serif;"
                        + "background:#f5f6f8;color:#1f2329}main{max-width:360px;margin:12vh auto;"
                        + "background:#fff;padding:32px;border-radius:12px;box-shadow:0 4px 24px "
                        + "rgba(0,0,0,.08)}h1{font-size:20px;margin:0 0 16px}"
                        + "label{display:block;margin:12px 0;font-size:14px}"
                        + "input[type=text],input[type=password]{display:block;width:100%;margin-top:6px;"
                        + "padding:8px 10px;border:1px solid #ccd0d6;border-radius:8px;font-size:14px;"
                        + "box-sizing:border-box}button{margin-top:12px;width:100%;padding:10px;"
                        + "border:0;border-radius:8px;background:#1f6feb;color:#fff;font-size:15px;"
                        + "cursor:pointer}button.secondary{background:#e6e8eb;color:#1f2329}"
                        + ".notice{padding:8px 10px;border-radius:8px;background:#eef3ff;font-size:14px}"
                        + ".notice.error{background:#fdecec;color:#a8071a}"
                        + ".scope{margin:8px 0}.scope input{margin-right:8px}"
                        + "</style></head><body><main>"
                        + body
                        + "</main></body></html>";
        return ResponseEntity.status(status)
                .contentType(HTML)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header("X-Frame-Options", "DENY")
                .header("Referrer-Policy", "no-referrer")
                .header("Content-Security-Policy", CONTENT_SECURITY_POLICY)
                .body(document);
    }

    private static String csrfToken(HttpServletRequest request) {
        Object value = request.getAttribute(CsrfToken.class.getName());
        return value instanceof CsrfToken token ? token.getToken() : "";
    }

    private static String escape(String value) {
        return value == null ? "" : HtmlUtils.htmlEscape(value);
    }
}
