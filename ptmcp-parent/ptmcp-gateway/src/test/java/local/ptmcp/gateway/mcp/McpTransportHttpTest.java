package local.ptmcp.gateway.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import local.ptmcp.gateway.GatewayApplication;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Real HTTP transport with isolated identity/tool boundaries, not a PAT/OAuth acceptance test. */
@SpringBootTest(
        classes = GatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ptmcp.gateway.allowed-origins=https://client.invalid")
class McpTransportHttpTest {
    private static final String VERSION = "2025-06-18";
    private static final String MODERN_VERSION = "2026-07-28";
    private static final String LIST = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
    private static final String MODERN_LIST =
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\","
                    + "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\"}}";
    private static final String DISCOVER =
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"server/discover\","
                    + "\"_meta\":{\"io.modelcontextprotocol/protocolVersion\":\"2026-07-28\"}}";
    private static final String INITIALIZE =
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                    + "\"params\":{\"protocolVersion\":\"2025-06-18\"}}";

    @LocalServerPort private int port;
    @MockitoBean private McpAuthentication authentication;
    @MockitoBean private McpToolService tools;

    @BeforeEach
    void prepare() {
        Instant now = Instant.now();
        when(authentication.authenticate(anyString(), any()))
                .thenReturn(
                        new McpIdentity(
                                1, 2, 1, "test", "test", Set.of(), now.plusSeconds(60), 1, now));
        when(tools.handle(any(), anyString(), anyString()))
                .thenReturn(
                        new McpToolService.Outcome(
                                200, "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"));
    }

    @Test
    void missingOrExplicitlyAllowedOriginStillRequiresAuthentication() throws Exception {
        for (String origin : List.of("", "https://client.invalid")) {
            HttpRequest.Builder request = request(LIST).header("MCP-Protocol-Version", VERSION);
            if (!origin.isEmpty()) {
                request.header("Origin", origin);
            }
            HttpResponse<String> response = send(request);
            assertEquals(401, response.statusCode());
            assertTrue(
                    response.headers()
                            .firstValue("WWW-Authenticate")
                            .orElse("")
                            .startsWith("Bearer"));
        }
        verifyNoInteractions(authentication, tools);
    }

    @Test
    void noOriginAndNormalizedAllowlistedOriginReachAuthenticatedDispatcher() throws Exception {
        for (String origin : List.of("", "https://client.invalid", "HTTPS://CLIENT.INVALID:443")) {
            HttpRequest.Builder request = authorized(LIST).header("MCP-Protocol-Version", VERSION);
            if (!origin.isEmpty()) {
                request.header("Origin", origin);
            }
            assertEquals(200, send(request).statusCode());
        }
    }

    @Test
    void untrustedOrMalformedOriginsRejectedBeforeAuthentication() throws Exception {
        for (String origin :
                List.of(
                        "null",
                        "https://evil.invalid",
                        "https://client.invalid.evil.invalid",
                        "http://client.invalid",
                        "https://client.invalid:444",
                        "https://client.invalid/",
                        "https://user@client.invalid",
                        "https://client.invalid?x=1",
                        "https://client.invalid#x",
                        "https://client.invalid, https://evil.invalid",
                        "https://client.invalid https://evil.invalid",
                        "https://client.invalid:65536")) {
            HttpResponse<String> response =
                    send(
                            authorized(LIST)
                                    .header("Origin", origin)
                                    .header("MCP-Protocol-Version", VERSION)
                                    .header("X-Forwarded-Host", "client.invalid")
                                    .header("Forwarded", "host=client.invalid;proto=https"));
            assertEquals(403, response.statusCode(), origin);
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
            assertTrue(response.headers().firstValue("X-Request-ID").isPresent());
            assertFalse(response.body().contains(origin));
        }
        verifyNoInteractions(authentication, tools);
    }

    @Test
    void duplicateOriginRejectedEvenIfBothAreAllowed() throws Exception {
        assertEquals(
                403,
                send(authorized(LIST)
                                .header("Origin", "https://client.invalid")
                                .header("Origin", "https://client.invalid")
                                .header("MCP-Protocol-Version", VERSION))
                        .statusCode());
        verifyNoInteractions(authentication, tools);
    }

    @Test
    void initializeNegotiatesWithoutHeaderAndAcceptsSupportedHeader() throws Exception {
        assertEquals(200, send(authorized(INITIALIZE)).statusCode());
        assertEquals(
                200,
                send(authorized(INITIALIZE).header("MCP-Protocol-Version", VERSION)).statusCode());
    }

    @Test
    void missingSubsequentVersionAndInvalidExplicitVersionsRejectedBeforeTools() throws Exception {
        for (String body :
                List.of(
                        LIST,
                        "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                        "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{\"requestId\":1}}")) {
            assertEquals(400, send(authorized(body)).statusCode());
        }
        for (String version :
                List.of("", "2025-03-26", "2025-11-25", "invalid", "2025-06-18,2025-06-18")) {
            for (String body : List.of(LIST, INITIALIZE)) {
                assertEquals(
                        400,
                        send(authorized(body).header("MCP-Protocol-Version", version)).statusCode(),
                        version);
            }
        }
        verifyNoInteractions(tools);
    }

    @Test
    void modernVersionReachesDispatcherAndUnsupportedVersionStillRejected() throws Exception {
        // Modern tools/list with correct 2026-07-28 header reaches the mocked dispatcher.
        assertEquals(
                200,
                send(authorized(MODERN_LIST)
                                .header("MCP-Protocol-Version", MODERN_VERSION)
                                .header("Accept", "application/json, text/event-stream"))
                        .statusCode());
        // Modern server/discover reaches the dispatcher under the modern header.
        assertEquals(
                200,
                send(authorized(DISCOVER)
                                .header("MCP-Protocol-Version", MODERN_VERSION)
                                .header("Accept", "application/json, text/event-stream"))
                        .statusCode());
        // Unknown version still rejected before the dispatcher.
        assertEquals(
                400,
                send(authorized(MODERN_LIST)
                                .header("MCP-Protocol-Version", "2025-11-25")
                                .header("Accept", "application/json, text/event-stream"))
                        .statusCode());
        assertEquals(
                400,
                send(authorized(LIST)
                                .header("MCP-Protocol-Version", VERSION)
                                .header("MCP-Protocol-Version", VERSION))
                        .statusCode());
    }

    @Test
    void invalidIdsRetainRealJsonRpcErrorsUnlessExplicitVersionIsInvalid() throws Exception {
        // Exercise the real envelope validation, without any business dependencies or credentials.
        when(tools.handle(any(), anyString(), anyString())).thenCallRealMethod();
        var json = JsonMapper.builder().build();
        for (String id : List.of("true", "[]", "{}")) {
            String body = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/list\"}";
            for (boolean versionPresent : List.of(false, true)) {
                HttpRequest.Builder request = authorized(body);
                if (versionPresent) {
                    request.header("MCP-Protocol-Version", VERSION);
                }
                HttpResponse<String> response = send(request);
                assertEquals(200, response.statusCode(), body);
                var error = json.readTree(response.body());
                assertEquals(-32600, error.path("error").path("code").asInt());
                assertTrue(error.path("id").isNull());
            }
            for (String version : List.of("", "unsupported")) {
                assertEquals(
                        400,
                        send(authorized(body).header("MCP-Protocol-Version", version))
                                .statusCode());
            }
            assertEquals(
                    400,
                    send(authorized(body)
                                    .header("MCP-Protocol-Version", VERSION)
                                    .header("MCP-Protocol-Version", VERSION))
                            .statusCode());
        }
    }

    private HttpRequest.Builder request(String body) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private HttpRequest.Builder authorized(String body) {
        return request(body).header("Authorization", "Bearer synthetic-test-credential");
    }

    private static HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        try (HttpClient client =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(3))
                        .proxy(
                                new ProxySelector() {
                                    @Override
                                    public List<Proxy> select(URI uri) {
                                        return List.of(Proxy.NO_PROXY);
                                    }

                                    @Override
                                    public void connectFailed(
                                            URI uri, SocketAddress address, IOException error) {}
                                })
                        .build()) {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
    }
}
