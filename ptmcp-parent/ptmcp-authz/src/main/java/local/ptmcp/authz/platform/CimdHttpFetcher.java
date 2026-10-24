package local.ptmcp.authz.platform;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;

/**
 * The production {@link CimdResolver.MetadataFetcher} over the JDK HTTP client. Redirects are never
 * followed (a 3xx answer raises {@link CimdResolver.RedirectFollowed} carrying the location), only
 * 2xx answers with an {@code application/json} content type are accepted, and the body is streamed
 * with a hard 32 KiB cap so an oversized document can never buffer. DNS failures, timeouts and
 * non-2xx statuses surface as {@code DEPENDENCY_UNAVAILABLE}. The pinned addresses are validated by
 * the resolver before this fetcher runs; they are carried on the interface so the connector layer
 * can bind the TCP connection to a verified address.
 */
public final class CimdHttpFetcher implements CimdResolver.MetadataFetcher {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);

    private final HttpClient client =
            HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(CONNECT_TIMEOUT)
                    .build();

    @Override
    public byte[] fetch(URI url, java.net.InetAddress[] pinnedAddresses) throws IOException {
        HttpRequest request;
        try {
            request =
                    HttpRequest.newBuilder(url)
                            .timeout(REQUEST_TIMEOUT)
                            .header("Accept", "application/json")
                            .GET()
                            .build();
        } catch (IllegalArgumentException failure) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        int status = response.statusCode();
        if (status >= 300 && status < 400) {
            String location =
                    response.headers()
                            .firstValue("Location")
                            .orElseThrow(
                                    () -> new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE));
            throw new CimdResolver.RedirectFollowed(url.resolve(location));
        }
        if (status < 200 || status >= 300) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        String contentType =
                response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        if (!contentType.startsWith("application/json")) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        try (InputStream body = response.body()) {
            byte[] buffer = new byte[CimdResolver.MAX_BYTES + 1];
            int offset = 0;
            int read;
            while (offset < buffer.length
                    && (read = body.read(buffer, offset, buffer.length - offset)) != -1) {
                offset += read;
            }
            if (offset > CimdResolver.MAX_BYTES) {
                throw new DomainException(ErrorCode.METADATA_TOO_LARGE);
            }
            return Arrays.copyOf(buffer, offset);
        }
    }
}
