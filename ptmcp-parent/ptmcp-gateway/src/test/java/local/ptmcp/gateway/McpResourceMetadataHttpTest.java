package local.ptmcp.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import tools.jackson.databind.json.JsonMapper;

import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@SpringBootTest(
        classes = GatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "ptmcp.gateway.resource=ptmcp-mcp",
            "ptmcp.gateway.resource-uri=https://gateway.example/mcp",
            "ptmcp.gateway.issuer=https://auth.example",
            "ptmcp.gateway.resource-metadata-uri=https://gateway.example/.well-known/oauth-protected-resource"
        })
class McpResourceMetadataHttpTest {
    @LocalServerPort private int port;

    @Test
    void anonymousDiscoveryAndChallengeUseOnlyTrustedConfiguration() throws Exception {
        try (HttpClient client = HttpClient.newBuilder().proxy(ProxySelector.of(null)).build()) {
            for (String path :
                    new String[] {
                        "/.well-known/oauth-protected-resource",
                        "/.well-known/oauth-protected-resource/mcp"
                    }) {
                var response =
                        client.send(
                                request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, response.statusCode());
                var body = JsonMapper.builder().build().readTree(response.body());
                assertEquals("https://gateway.example/mcp", body.path("resource").asText());
                assertEquals(
                        "https://auth.example", body.path("authorization_servers").get(0).asText());
                assertEquals("header", body.path("bearer_methods_supported").get(0).asText());
                assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(""));
                assertFalse(response.body().contains("attacker.invalid"));
            }
            var response =
                    client.send(
                            request("/mcp").POST(HttpRequest.BodyPublishers.noBody()).build(),
                            HttpResponse.BodyHandlers.ofString());
            assertEquals(401, response.statusCode());
            assertEquals(
                    "Bearer realm=\"PTMCP\", resource_metadata=\"https://gateway.example/.well-known/oauth-protected-resource\"",
                    response.headers().firstValue("WWW-Authenticate").orElse(""));
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("X-Forwarded-Host", "attacker.invalid")
                .header("Forwarded", "host=attacker.invalid;proto=http");
    }
}
