package local.ptmcp.datasource;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

/** One pool generation owned by {@link DataSourcePoolRegistry}. */
public final class PoolHandle {
    public enum State {
        ACTIVE,
        DRAINING,
        CLOSED
    }

    private final PoolKey key;
    private final long generation;
    private final DataSource dataSource;
    private final AtomicInteger evictedConnections = new AtomicInteger();
    private volatile State state = State.ACTIVE;
    private volatile boolean disposalFailed;
    private int leases;

    PoolHandle(PoolKey key, long generation, DataSource dataSource) {
        this.key = Objects.requireNonNull(key, "key");
        this.generation = generation;
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public PoolKey key() {
        return key;
    }

    public long generation() {
        return generation;
    }

    public State state() {
        return state;
    }

    public boolean disposalFailed() {
        return disposalFailed;
    }

    public int evictedConnections() {
        return evictedConnections.get();
    }

    /**
     * The generation's pool. Exposed only so the engine-specific approved-plan adapters in the
     * trusted execution path can bind it; request-facing code must never reach a DataSource.
     */
    public DataSource dataSource() {
        return dataSource;
    }

    /** Pins this exact generation until execution, cancellation and cleanup have finished. */
    public synchronized Lease lease() {
        if (state != State.ACTIVE) {
            throw new DomainException(ErrorCode.RESOURCE_VERSION_CHANGED);
        }
        leases++;
        return new Lease();
    }

    public final class Lease implements AutoCloseable {
        private final AtomicBoolean released = new AtomicBoolean();

        private Lease() {}

        public DataSource dataSource() {
            if (released.get()) {
                throw new IllegalStateException("lease is released");
            }
            return dataSource;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                release();
            }
        }
    }

    synchronized void beginDrain() {
        if (state == State.ACTIVE) {
            state = State.DRAINING;
            closeIfDrained();
        }
    }

    private synchronized void release() {
        leases--;
        closeIfDrained();
    }

    synchronized void retryDisposal() {
        closeIfDrained();
    }

    private void closeIfDrained() {
        if (state != State.DRAINING || leases != 0) {
            return;
        }
        if (dataSource instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                disposalFailed = true;
                return;
            }
        }
        disposalFailed = false;
        state = State.CLOSED;
    }

    void evict(Connection connection) {
        evictedConnections.incrementAndGet();
        try {
            connection.abort(Runnable::run);
        } catch (SQLException ignored) {
            // The physical close below is the fallback; the connection is never returned.
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // A failed close still leaves the connection outside the live generation.
        }
    }
}
