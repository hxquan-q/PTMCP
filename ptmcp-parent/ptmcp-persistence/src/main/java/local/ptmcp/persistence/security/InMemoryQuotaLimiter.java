package local.ptmcp.persistence.security;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Single-node quota implementation. Counters are process-local; a multi-node deployment needs a
 * shared limiter (Redis) before gateway-wide numbers are treated as cluster truth. Acquisition
 * order inside {@link #acquireExecution} is user, datasource, gateway total; any failure rolls back
 * the permits already taken so a rejected request never leaks capacity.
 */
public final class InMemoryQuotaLimiter implements QuotaLimiter {
    private final LongSupplier monotonicClock;
    private final AtomicInteger executing = new AtomicInteger();
    private final AtomicInteger queued = new AtomicInteger();
    private final ConcurrentHashMap<Long, AtomicInteger> perUser = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AtomicInteger> perDatasource = new ConcurrentHashMap<>();

    public InMemoryQuotaLimiter() {
        this(System::nanoTime);
    }

    /** Single-clock seam so tests can drive queue expiry deterministically. */
    InMemoryQuotaLimiter(LongSupplier monotonicClock) {
        this.monotonicClock = Objects.requireNonNull(monotonicClock, "monotonicClock");
    }

    @Override
    public QueuePermit acquireQueue(long userId) {
        if (userId <= 0) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (!incrementWithin(queued, QUEUED_MAX)) {
            throw new DomainException(ErrorCode.GATEWAY_BUSY);
        }
        return new QueuePermit(userId, monotonicClock.getAsLong() + QUEUE_WINDOW_NANOS);
    }

    @Override
    public ExecutionPermit acquireExecution(long userId, long datasourceId, QueuePermit queuedAt) {
        if (userId <= 0 || datasourceId <= 0 || queuedAt == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (queuedAt.userId() != userId || monotonicClock.getAsLong() > queuedAt.expiresAtNanos()) {
            release(queuedAt);
            throw new DomainException(ErrorCode.GATEWAY_BUSY);
        }
        AtomicInteger userGauge = gauge(perUser, userId);
        AtomicInteger datasourceGauge = gauge(perDatasource, datasourceId);
        boolean userTaken = false;
        boolean datasourceTaken = false;
        boolean gatewayTaken = false;
        try {
            if (!incrementWithin(userGauge, USER_MAX)) {
                throw new DomainException(ErrorCode.USER_CONCURRENCY_LIMIT);
            }
            userTaken = true;
            if (!incrementWithin(datasourceGauge, DATASOURCE_MAX)) {
                throw new DomainException(ErrorCode.DATASOURCE_CONCURRENCY_LIMIT);
            }
            datasourceTaken = true;
            if (!incrementWithin(executing, GATEWAY_MAX)) {
                throw new DomainException(ErrorCode.GATEWAY_BUSY);
            }
            gatewayTaken = true;
        } catch (RuntimeException error) {
            if (userTaken) {
                decrementWithinZero(userGauge);
            }
            if (datasourceTaken) {
                decrementWithinZero(datasourceGauge);
            }
            if (gatewayTaken) {
                decrementWithinZero(executing);
            }
            // A rejected request has left the queue; free its slot here so callers cannot leak it.
            release(queuedAt);
            throw error;
        }
        release(queuedAt);
        return new ExecutionPermit(userId, datasourceId);
    }

    @Override
    public void release(ExecutionPermit permit) {
        Objects.requireNonNull(permit, "permit");
        decrementWithinZero(gauge(perUser, permit.userId()));
        decrementWithinZero(gauge(perDatasource, permit.datasourceId()));
        decrementWithinZero(executing);
    }

    @Override
    public void release(QueuePermit permit) {
        Objects.requireNonNull(permit, "permit");
        decrementWithinZero(queued);
    }

    private static AtomicInteger gauge(ConcurrentHashMap<Long, AtomicInteger> gauges, long id) {
        return gauges.computeIfAbsent(id, ignored -> new AtomicInteger());
    }

    private static boolean incrementWithin(AtomicInteger gauge, int max) {
        int current;
        do {
            current = gauge.get();
            if (current >= max) {
                return false;
            }
        } while (!gauge.compareAndSet(current, current + 1));
        return true;
    }

    private static void decrementWithinZero(AtomicInteger gauge) {
        int current;
        do {
            current = gauge.get();
            if (current <= 0) {
                return;
            }
        } while (!gauge.compareAndSet(current, current - 1));
    }
}
