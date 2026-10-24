package local.ptmcp.authz.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** CIMD resolution: DNS wall, budgets, caching, and the real HTTP fetcher behavior. */
class CimdResolverTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static HttpServer http;

    @BeforeAll
    static void startLocalServer() throws IOException {
        http =
                HttpServer.create(
                        new java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        http.createContext(
                "/metadata",
                exchange -> {
                    byte[] body =
                            "{\"issuer\":\"https://client.example\",\"response_types\":[\"code\"]}"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                });
        http.createContext(
                "/redirect",
                exchange -> {
                    exchange.getResponseHeaders().set("Location", "/metadata");
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                });
        http.createContext(
                "/huge",
                exchange -> {
                    byte[] chunk = new byte[8192];
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, chunk.length * 6);
                    try (OutputStream out = exchange.getResponseBody()) {
                        for (int i = 0; i < 6; i++) {
                            out.write(chunk);
                        }
                    }
                });
        http.createContext(
                "/html",
                exchange -> {
                    byte[] body = "<html/>".getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/html");
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                });
        http.createContext("/notfound", exchange -> exchange.sendResponseHeaders(404, -1));
        http.start();
    }

    @AfterAll
    static void stopLocalServer() {
        if (http != null) {
            http.stop(0);
        }
    }

    @Test
    void publicIpLiteralMetadataPassesPolicyAndParsesThroughFakeFetch() {
        AtomicLong nanos = new AtomicLong(0);
        AtomicInteger fetches = new AtomicInteger();
        CimdResolver resolver =
                new CimdResolver(
                        host -> new InetAddress[] {address("93.184.216.34")},
                        (url, pinned) -> {
                            fetches.incrementAndGet();
                            return "{\"client_name\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
                        },
                        nanos::get,
                        Duration.ofSeconds(300));

        JsonNode metadata =
                resolver.fetch(URI.create("https://client.example/.well-known/oauth-client"));
        assertEquals("ok", metadata.get("client_name").asText());
        assertEquals(1, fetches.get());

        resolver.fetch(URI.create("https://client.example/.well-known/oauth-client"));
        assertEquals(1, fetches.get(), "fresh cache hit must not refetch");

        nanos.set(Duration.ofSeconds(301).toNanos());
        resolver.fetch(URI.create("https://client.example/.well-known/oauth-client"));
        assertEquals(2, fetches.get(), "expired cache entry refetches");
    }

    @Test
    void privateAndMetadataIpLiteralsAreRejectedBeforeAnyFetch() {
        AtomicInteger fetches = new AtomicInteger();
        CimdResolver resolver =
                new CimdResolver(
                        host -> {
                            throw new AssertionError("IP literals must not go through DNS");
                        },
                        (url, pinned) -> {
                            fetches.incrementAndGet();
                            return "{}".getBytes(StandardCharsets.UTF_8);
                        },
                        () -> 0,
                        Duration.ofSeconds(300));
        for (String bad :
                List.of(
                        "https://127.0.0.1/.well-known/oauth-client",
                        "https://[::1]/.well-known/oauth-client",
                        "https://169.254.169.254/latest/meta-data",
                        "https://10.1.2.3/.well-known/oauth-client",
                        "https://192.168.5.5/.well-known/oauth-client",
                        "https://[::ffff:10.0.0.5]/.well-known/oauth-client")) {
            DomainException error =
                    assertThrows(DomainException.class, () -> resolver.fetch(URI.create(bad)), bad);
            assertEquals(ErrorCode.INVALID_ARGUMENT, error.errorCode(), bad);
        }
        assertEquals(0, fetches.get(), "nothing may be fetched from blocked networks");
    }

    @Test
    void dnsResolutionFailureAndPrivateResolutionFailClosed() {
        CimdResolver resolver =
                new CimdResolver(
                        host -> {
                            throw new UnknownHostException(host);
                        },
                        (url, pinned) -> "{}".getBytes(StandardCharsets.UTF_8),
                        () -> 0,
                        Duration.ofSeconds(300));
        DomainException unresolved =
                assertThrows(
                        DomainException.class,
                        () ->
                                resolver.fetch(
                                        URI.create(
                                                "https://missing.example/.well-known/oauth-client")));
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, unresolved.errorCode());

        CimdResolver privateResolver =
                new CimdResolver(
                        host -> new InetAddress[] {address("10.0.0.9")},
                        (url, pinned) -> "{}".getBytes(StandardCharsets.UTF_8),
                        () -> 0,
                        Duration.ofSeconds(300));
        DomainException privateHit =
                assertThrows(
                        DomainException.class,
                        () ->
                                privateResolver.fetch(
                                        URI.create(
                                                "https://internal.example/.well-known/oauth-client")));
        assertEquals(ErrorCode.INVALID_ARGUMENT, privateHit.errorCode());
    }

    @Test
    void oversizedPayloadsAreRejectedByTheResolverBudget() {
        CimdResolver resolver =
                new CimdResolver(
                        host -> new InetAddress[] {address("93.184.216.34")},
                        (url, pinned) -> new byte[48 * 1024],
                        () -> 0,
                        Duration.ofSeconds(300));
        DomainException tooLarge =
                assertThrows(
                        DomainException.class,
                        () ->
                                resolver.fetch(
                                        URI.create(
                                                "https://client.example/.well-known/oauth-client")));
        assertEquals(ErrorCode.METADATA_TOO_LARGE, tooLarge.errorCode());
    }

    @Test
    void realFetcherEnforcesJsonOnlyStatusesSizeCapAndNeverRedirects() throws Exception {
        String base =
                "http://" + http.getAddress().getHostString() + ":" + http.getAddress().getPort();
        CimdHttpFetcher fetcher = new CimdHttpFetcher();
        InetAddress[] pinned = new InetAddress[] {address("93.184.216.34")};

        byte[] metadata = fetcher.fetch(URI.create(base + "/metadata"), pinned);
        assertTrue(new String(metadata, StandardCharsets.UTF_8).contains("issuer"));

        assertEquals(
                ErrorCode.METADATA_TOO_LARGE,
                assertThrows(
                                DomainException.class,
                                () -> fetcher.fetch(URI.create(base + "/huge"), pinned))
                        .errorCode());
        assertEquals(
                ErrorCode.INVALID_ARGUMENT,
                assertThrows(
                                DomainException.class,
                                () -> fetcher.fetch(URI.create(base + "/html"), pinned))
                        .errorCode());
        assertEquals(
                ErrorCode.DEPENDENCY_UNAVAILABLE,
                assertThrows(
                                DomainException.class,
                                () -> fetcher.fetch(URI.create(base + "/notfound"), pinned))
                        .errorCode());

        CimdResolver.RedirectFollowed failure =
                assertThrows(
                        CimdResolver.RedirectFollowed.class,
                        () -> fetcher.fetch(URI.create(base + "/redirect"), pinned));
        assertEquals("/metadata", failure.location().getPath());
    }

    @Test
    void deepMetadataIsRejectedByBudget() {
        CimdResolver resolver =
                new CimdResolver(
                        host -> new InetAddress[] {address("93.184.216.34")},
                        (url, pinned) -> {
                            StringBuilder json = new StringBuilder();
                            for (int i = 0; i < 12; i++) {
                                json.append("{\"n\":");
                            }
                            json.append("1");
                            for (int i = 0; i < 12; i++) {
                                json.append("}");
                            }
                            return json.toString().getBytes(StandardCharsets.UTF_8);
                        },
                        () -> 0,
                        Duration.ofSeconds(300));
        DomainException error =
                assertThrows(
                        DomainException.class,
                        () ->
                                resolver.fetch(
                                        URI.create(
                                                "https://client.example/.well-known/oauth-client")));
        assertEquals(ErrorCode.INVALID_ARGUMENT, error.errorCode());
        assertTrue(true);
    }

    private static InetAddress address(String value) {
        try {
            return InetAddress.getByName(value);
        } catch (UnknownHostException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
