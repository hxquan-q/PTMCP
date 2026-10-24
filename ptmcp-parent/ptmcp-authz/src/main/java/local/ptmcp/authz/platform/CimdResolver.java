package local.ptmcp.authz.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Resolves a CIMD client-metadata URL behind the full SSRF wall: the URL must satisfy {@link
 * CimdPolicy#validateUrl}, IP-literal hosts are validated without any DNS traffic, hostname
 * resolution goes through the injected {@link DnsResolver} and every resolved address is checked by
 * {@link CimdPolicy#validateAddresses} before a byte is fetched. Responses are capped at 32 KiB and
 * a JSON nesting depth of 8; DNS failures and non-200 fetches fail closed with {@code
 * DEPENDENCY_UNAVAILABLE}. Verified metadata is cached per URL for the configured TTL.
 */
public final class CimdResolver {
    static final int MAX_BYTES = 32 * 1024;
    private static final int MAX_DEPTH = 8;

    public interface DnsResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    public interface MetadataFetcher {
        byte[] fetch(URI url, InetAddress[] pinnedAddresses) throws IOException;
    }

    /**
     * Raised by a fetcher when the server answered with a redirect; redirects are never followed.
     */
    public static final class RedirectFollowed extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final URI location;

        public RedirectFollowed(URI location) {
            super("metadata endpoint answered with a redirect");
            this.location = location;
        }

        public URI location() {
            return location;
        }
    }

    private record CachedMetadata(JsonNode json, long cachedAtNanos) {}

    private final DnsResolver dns;
    private final MetadataFetcher fetcher;
    private final LongSupplier nanos;
    private final long cacheTtlNanos;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, CachedMetadata> cache = new ConcurrentHashMap<>();

    public CimdResolver(
            DnsResolver dns, MetadataFetcher fetcher, LongSupplier nanos, Duration cacheTtl) {
        this.dns = dns;
        this.fetcher = fetcher;
        this.nanos = nanos;
        this.cacheTtlNanos = cacheTtl.toNanos();
    }

    public JsonNode fetch(URI uri) {
        if (uri == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        CimdPolicy.validateUrl(uri.toString());
        InetAddress[] pinned = pin(uri.getHost());
        CimdPolicy.validateAddresses(pinned);
        String key = uri.toString();
        long now = nanos.getAsLong();
        CachedMetadata cached = cache.get(key);
        if (cached != null && now - cached.cachedAtNanos() < cacheTtlNanos) {
            return cached.json();
        }
        byte[] payload;
        try {
            payload = fetcher.fetch(uri, pinned);
        } catch (RedirectFollowed failure) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        } catch (IOException failure) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
        if (payload == null || payload.length > MAX_BYTES) {
            throw new DomainException(ErrorCode.METADATA_TOO_LARGE);
        }
        JsonNode json;
        try {
            json = mapper.readTree(payload);
        } catch (IOException failure) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (json == null || depth(json) > MAX_DEPTH) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        cache.put(key, new CachedMetadata(json, now));
        return json;
    }

    private InetAddress[] pin(String host) {
        if (host == null || host.isEmpty()) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (isTextualIpLiteral(host)) {
            return new InetAddress[] {parseLiteral(host)};
        }
        try {
            return dns.resolve(host);
        } catch (UnknownHostException failure) {
            throw new DomainException(ErrorCode.DEPENDENCY_UNAVAILABLE);
        }
    }

    private static boolean isTextualIpLiteral(String host) {
        if (host.indexOf(':') >= 0) {
            return true;
        }
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (!part.matches("\\d{1,3}")) {
                return false;
            }
        }
        return true;
    }

    private static InetAddress parseLiteral(String host) {
        try {
            return InetAddress.getByName(host);
        } catch (UnknownHostException failure) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
    }

    private static int depth(JsonNode node) {
        int childDepth = 0;
        if (node.isContainerNode()) {
            for (JsonNode child : node) {
                childDepth = Math.max(childDepth, depth(child));
            }
            return 1 + childDepth;
        }
        return 0;
    }
}
