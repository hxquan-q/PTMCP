package local.ptmcp.persistence.security;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Snapshot freshness and availability gate between the security state reader and request handlers.
 * The gate holds only what the reader produced; it never renews {@code checkedAt} on lookup and
 * never trusts an entry older than the authoritative staleness bound. Consecutive reader failures
 * trip a reject-until-success latch: an unavailable metadata primary must block authorization, not
 * fall back to a stale snapshot.
 */
public final class SecurityStateGuard {
    static final long MAX_STALENESS_NANOS = Duration.ofSeconds(5).toNanos();
    static final long LOCAL_CACHE_TTL_NANOS = Duration.ofSeconds(1).toNanos();
    static final int MAX_CONSECUTIVE_READ_FAILURES = 3;

    private final LongSupplier monotonicClock;
    private final Map<CacheKey, CachedSnapshot> cache = new LinkedHashMap<>(64, 0.75f, true);
    private final AtomicInteger consecutiveReadFailures = new AtomicInteger();

    public SecurityStateGuard() {
        this(System::nanoTime);
    }

    /** Single-clock seam so tests can drive cache expiry deterministically. */
    SecurityStateGuard(LongSupplier monotonicClock) {
        this.monotonicClock = Objects.requireNonNull(monotonicClock, "monotonicClock");
    }

    /** Returns the cached snapshot only inside the local TTL; expiry evicts and returns empty. */
    public SecuritySnapshot cached(CacheKey key) {
        Objects.requireNonNull(key, "key");
        long now = monotonicClock.getAsLong();
        synchronized (cache) {
            CachedSnapshot entry = cache.get(key);
            if (entry == null) {
                return null;
            }
            if (now - entry.storedAtNanos > LOCAL_CACHE_TTL_NANOS) {
                cache.remove(key);
                return null;
            }
            return entry.snapshot;
        }
    }

    /** Caches only a snapshot that is still authoritatively fresh at call time. */
    public void cache(CacheKey key, SecuritySnapshot snapshot) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(snapshot, "snapshot");
        Instant now = Instant.now();
        if (!snapshot.isFresh(now)) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        synchronized (cache) {
            cache.put(key, new CachedSnapshot(snapshot, monotonicClock.getAsLong()));
        }
    }

    /** True only when the snapshot is inside the authoritative 5s window. */
    public boolean isUsable(SecuritySnapshot snapshot, Instant now) {
        if (snapshot == null || now == null) {
            return false;
        }
        return snapshot.isFresh(now);
    }

    /**
     * True when the caller must reject instead of authorizing from a stale state. Trips after
     * {@link #MAX_CONSECUTIVE_READ_FAILURES} consecutive reader failures and stays tripped until a
     * success is recorded: fail closed rather than authorize against an old snapshot.
     */
    public boolean shouldRejectReads() {
        return consecutiveReadFailures.get() >= MAX_CONSECUTIVE_READ_FAILURES;
    }

    public void recordReadFailure() {
        int current;
        do {
            current = consecutiveReadFailures.get();
            if (current == MAX_CONSECUTIVE_READ_FAILURES) {
                break;
            }
        } while (!consecutiveReadFailures.compareAndSet(current, current + 1));
        synchronized (cache) {
            cache.clear();
        }
    }

    public void recordReadSuccess() {
        consecutiveReadFailures.set(0);
    }

    /** Request identity for the snapshot cache; family order is normalized. */
    public record CacheKey(long platformId, Long userId, List<UUID> familyIds) {
        public CacheKey {
            if (platformId <= 0) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
            if (userId != null && userId <= 0) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
            if (familyIds == null) {
                familyIds = List.of();
            } else {
                List<UUID> distinct = familyIds.stream().distinct().toList();
                if (distinct.size() > SecurityStateReader.MAX_FAMILIES) {
                    throw new DomainException(ErrorCode.INVALID_ARGUMENT);
                }
                familyIds = List.copyOf(distinct);
            }
        }
    }

    private record CachedSnapshot(SecuritySnapshot snapshot, long storedAtNanos) {}
}
