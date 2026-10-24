package local.ptmcp.authz.oauth;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.HtmlUtils;

import java.util.LinkedHashSet;
import java.util.Set;

/** Minimal server-rendered pages. All request values are escaped; no script is allowed by CSP. */
@Controller
@ConditionalOnProperty(prefix = "ptmcp.authz.oauth", name = "enabled", havingValue = "true")
public class OAuthPageController {
    static final MediaType HTML = MediaType.parseMediaType("text/html;charset=UTF-8");
    static final String CONTENT_SECURITY_POLICY =
            "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; "
                    + "frame-ancestors 'none'; base-uri 'none'";

    private final PtmcpRegisteredClientRepository clients;
    private final AuthorizationServerSettings settings;

    public OAuthPageController(
            PtmcpRegisteredClientRepository clients, AuthorizationServerSettings settings) {
        this.clients = clients;
        this.settings = settings;
    }

    @GetMapping(OAuthSecurityConfiguration.LOGIN_PAGE)
    public ResponseEntity<String> login(
            HttpServletRequest request,
            @RequestParam(name = "error", required = false) String error,
            @RequestParam(name = "logout", required = false) String logout) {
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
            @RequestParam(name = "client_id", required = false) String clientId,
            @RequestParam(name = "scope", required = false) String scope,
            @RequestParam(name = "state", required = false) String state) {
        if (!StringUtils.hasText(clientId) || !StringUtils.hasText(state)) {
            return errorPage("授权请求缺少必要参数。");
        }
        RegisteredClient client = clients.findByClientId(clientId);
        if (client == null) {
            return errorPage("未知的客户端。");
        }
        Set<String> requested = new LinkedHashSet<>();
        if (StringUtils.hasText(scope)) {
            for (String value : scope.trim().split("\\s+")) {
                if (StringUtils.hasText(value) && client.getScopes().contains(value)) {
                    requested.add(value);
                }
            }
        }

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
            boxes.append("<label class=\"scope\"><input type=\"checkbox\" name=\"scope\" value=\"")
                    .append(escape(value))
                    .append("\" checked> <span>")
                    .append(escape(value))
                    .append("</span></label>");
        }
        String body =
                "<h1>授权确认</h1>"
                        + "<p>应用 <strong>"
                        + escape(client.getClientName())
                        + "</strong> 请求访问以下权限：</p>"
                        + "<form method=\"post\" action=\""
                        + action
                        + "\">"
                        + hidden
                        + boxes
                        + "<button type=\"submit\">允许</button>"
                        + "</form>"
                        + "<form method=\"post\" action=\""
                        + action
                        + "\">"
                        + hidden
                        + "<button type=\"submit\" class=\"secondary\">拒绝</button>"
                        + "</form>";
        return html(HttpStatus.OK, "授权确认", body);
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
