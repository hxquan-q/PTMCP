package local.ptmcp.datasource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
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

    public int evictedConnections() {
        return evictedConnections.get();
    }

    DataSource dataSource() {
        return dataSource;
    }

    void beginDrain() {
        state = State.DRAINING;
    }

    void markClosed() {
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
