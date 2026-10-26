package local.ptmcp.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** The public endpoint must challenge every request, even without configured business services. */
@SpringBootTest(
        classes = GatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpHttpBoundaryTest {
    @LocalServerPort private int port;

    @Test
    void missingCredentialsChallengedWithoutTrustingForwardedHost() throws Exception {
        try (HttpClient client =
                HttpClient.newBuilder()
                        .proxy(
                                new java.net.ProxySelector() {
                                    @Override
                                    public java.util.List<java.net.Proxy> select(URI uri) {
                                        return java.util.List.of(java.net.Proxy.NO_PROXY);
                                    }

                                    @Override
                                    public void connectFailed(
                                            URI uri,
                                            java.net.SocketAddress address,
                                            java.io.IOException error) {}
                                })
                        .build()) {
            HttpRequest request =
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                            .header("Content-Type", "application/json")
                            .header("X-Forwarded-Host", "attacker.invalid")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                            .build();
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(401, response.statusCode());
            assertTrue(
                    response.headers()
                            .firstValue("WWW-Authenticate")
                            .orElse("")
                            .startsWith("Bearer"));
            assertTrue(!response.body().contains("attacker.invalid"));
        }
    }
}
