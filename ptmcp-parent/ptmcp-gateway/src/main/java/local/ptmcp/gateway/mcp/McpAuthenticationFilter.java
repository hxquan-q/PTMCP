package local.ptmcp.gateway.mcp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/** Stateless authentication on every HTTP request; no session/cookie/header identity fallback. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class McpAuthenticationFilter extends OncePerRequestFilter {
    public static final String IDENTITY = McpAuthenticationFilter.class.getName() + ".identity";
    public static final String REQUEST_ID = McpAuthenticationFilter.class.getName() + ".requestId";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ObjectProvider<McpAuthentication> authentication;
    private final String metadataUri;

    public McpAuthenticationFilter(
            ObjectProvider<McpAuthentication> authentication,
            @Value("${ptmcp.gateway.resource-metadata-uri:}") String metadataUri) {
        this.authentication = authentication;
        this.metadataUri = metadataUri;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"/mcp".equals(request.getServletPath());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        request.setAttribute(REQUEST_ID, requestId);
        response.setHeader("X-Request-ID", requestId);
        response.setHeader("Cache-Control", "no-store");
        try {
            var headers = Collections.list(request.getHeaders("Authorization"));
            if (headers.isEmpty()) {
                throw new DomainException(ErrorCode.AUTHENTICATION_REQUIRED);
            }
            if (headers.size() != 1
                    || !headers.getFirst().startsWith("Bearer ")
                    || headers.getFirst().length() > 8192) {
                throw new DomainException(ErrorCode.TOKEN_INVALID);
            }
            String bearer = headers.getFirst().substring(7);
            if (bearer.isBlank() || bearer.chars().anyMatch(Character::isWhitespace)) {
                throw new DomainException(ErrorCode.TOKEN_INVALID);
            }
            McpAuthentication verifier = authentication.getIfAvailable();
            if (verifier == null) {
                throw new DomainException(ErrorCode.SECURITY_STATE_UNAVAILABLE);
            }
            request.setAttribute(IDENTITY, verifier.authenticate(bearer, request));
        } catch (DomainException failure) {
            ErrorCode code = failure.errorCode();
            int status =
                    code == ErrorCode.PLATFORM_MISMATCH || code == ErrorCode.PLATFORM_PROOF_REQUIRED
                            ? 403
                            : code == ErrorCode.SECURITY_STATE_UNAVAILABLE ? 503 : 401;
            response.setStatus(status);
            if (status == 401) {
                response.setHeader(
                        "WWW-Authenticate",
                        "Bearer realm=\"PTMCP\""
                                + (metadataUri.isBlank()
                                        ? ""
                                        : ", resource_metadata=\"" + metadataUri + "\""));
            }
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter()
                    .write(
                            JSON.writeValueAsString(
                                    Map.of(
                                            "requestId",
                                            requestId,
                                            "error",
                                            Map.of("code", code.name()))));
            return;
        }
        chain.doFilter(request, response);
    }
}
