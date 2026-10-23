package local.ptmcp.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Framework startup coverage only; not authentication, MCP, or business acceptance. */
@SpringBootTest(
        classes = GatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayApplicationHttpStartupTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort private int port;

    @Test
    void healthReturnsMinimalUpStatusWithoutComponentDetailsOverHttp() throws Exception {
        HttpResponse<String> response = get("/actuator/health");

        assertEquals(200, response.statusCode());
        assertJsonContentType(response);
        JsonNode health = JSON.readTree(response.body());
        assertTrue(health.isObject());
        assertEquals(JSON.readTree("\"UP\""), health.get("status"));
        // Boot may advertise its probe group names; component health and details stay private.
        if (health.has("groups")) {
            assertEquals(JSON.readTree("[\"liveness\",\"readiness\"]"), health.get("groups"));
        }
        assertEquals(health.has("groups") ? 2 : 1, health.size());
    }

    @Test
    void missingProbePathReturns404WithoutErrorDetailsEvenWhenRequested() throws Exception {
        // Keep this probe outside business routes so adding APIs does not invalidate startup
        // coverage.
        HttpResponse<String> response =
                get("/__ptmcp_startup_probe__/missing?trace=true&message=true");

        assertEquals(404, response.statusCode());
        assertJsonContentType(response);
        JsonNode error = JSON.readTree(response.body());
        assertTrue(error.isObject());
        assertEquals(JSON.readTree("404"), error.get("status"));
        for (String detail :
                new String[] {"message", "trace", "stacktrace", "stackTrace", "exception"}) {
            assertFalse(error.has(detail), () -> "Error response must not contain " + detail);
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        try (HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            HttpRequest request =
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                            .timeout(Duration.ofSeconds(10))
                            .header("Accept", "application/json")
                            .GET()
                            .build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        }
    }

    private static void assertJsonContentType(HttpResponse<String> response) {
        assertTrue(
                response.headers()
                        .firstValue("Content-Type")
                        .orElse("")
                        .startsWith("application/json"));
    }
}
