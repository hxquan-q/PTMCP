package local.ptmcp.gateway.mcp;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The single POST /mcp boundary. The authentication filter has already verified the bearer and
 * attached the identity; this controller enforces the body budget and content type, hands the
 * request to the tool service and maps only two non-200 outcomes: 202 for notifications and 503
 * when durable audit is unavailable. Tool-level failures stay inside JSON-RPC results.
 */
@RestController
final class McpToolController {
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ObjectProvider<McpToolService> toolService;

    McpToolController(ObjectProvider<McpToolService> toolService) {
        this.toolService = toolService;
    }

    @PostMapping("/mcp")
    void mcp(HttpServletRequest request, HttpServletResponse response) throws IOException {
        McpToolService service = toolService.getIfAvailable();
        if (service == null) {
            writeError(response, 503, ErrorCode.SECURITY_STATE_UNAVAILABLE, requestId(request));
            return;
        }
        String contentType = request.getContentType();
        if (contentType == null || !contentType.contains("application/json")) {
            response.setStatus(415);
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        if (body.length > MAX_BODY_BYTES) {
            response.setStatus(413);
            return;
        }
        McpIdentity identity = (McpIdentity) request.getAttribute(McpAuthenticationFilter.IDENTITY);
        if (identity == null) {
            writeError(response, 401, ErrorCode.AUTHENTICATION_REQUIRED, requestId(request));
            return;
        }
        McpToolService.Outcome outcome;
        try {
            outcome =
                    service.handle(
                            identity, new String(body, StandardCharsets.UTF_8), requestId(request));
        } catch (DomainException failure) {
            if (failure.errorCode() == ErrorCode.AUDIT_UNAVAILABLE) {
                writeError(response, 503, ErrorCode.AUDIT_UNAVAILABLE, requestId(request));
            } else {
                writeError(response, 500, ErrorCode.INTERNAL_ERROR, requestId(request));
            }
            return;
        }
        if (outcome.body().isEmpty()) {
            response.setStatus(outcome.status());
            return;
        }
        response.setStatus(outcome.status());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(outcome.body());
    }

    private static String requestId(HttpServletRequest request) {
        Object requestId = request.getAttribute(McpAuthenticationFilter.REQUEST_ID);
        return requestId == null ? null : requestId.toString();
    }

    private static void writeError(
            HttpServletResponse response, int status, ErrorCode code, String requestId) {
        try {
            response.setStatus(status);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter()
                    .write(
                            JSON.writeValueAsString(
                                    Map.of(
                                            "requestId",
                                            requestId == null ? "" : requestId,
                                            "error",
                                            Map.of("code", code.name()))));
        } catch (IOException ignored) {
            // The connection is gone; the failure is already decided.
        }
    }
}
