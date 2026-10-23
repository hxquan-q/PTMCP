package local.ptmcp.datasource;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import javax.sql.DataSource;

/**
 * Owns at most one active pool per datasource. A changed identity builds a new generation and
 * drains the previous one; a credential never becomes part of a key or an identifier.
 */
public final class DataSourcePoolRegistry implements AutoCloseable {
    private final PoolFactory poolFactory;
    private final Map<Long, PoolHandle> pools = new HashMap<>();
    private long generationSequence;
    private boolean closed;

    public DataSourcePoolRegistry() {
        this(new HikariPoolFactory());
    }

    public DataSourcePoolRegistry(PoolFactory poolFactory) {
        this.poolFactory = Objects.requireNonNull(poolFactory, "poolFactory");
    }

    public synchronized PoolHandle activate(PoolKey key, char[] credential, PoolLimits limits) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(credential, "credential");
        Objects.requireNonNull(limits, "limits");
        if (closed) {
            throw new IllegalStateException("registry is closed");
        }
        PoolHandle current = pools.get(key.datasourceId());
        if (current != null && current.key().equals(key)) {
            return current;
        }
        char[] copy = credential.clone();
        DataSource dataSource;
        try {
            dataSource = poolFactory.create(key, copy, limits);
        } finally {
            Arrays.fill(copy, '\0');
        }
        PoolHandle next = new PoolHandle(key, ++generationSequence, dataSource);
        if (current != null) {
            current.beginDrain();
            closePool(current.dataSource());
        }
        pools.put(key.datasourceId(), next);
        return next;
    }

    public synchronized Optional<PoolHandle> current(long datasourceId) {
        return Optional.ofNullable(pools.get(datasourceId));
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (PoolHandle handle : pools.values()) {
            handle.beginDrain();
            closePool(handle.dataSource());
            handle.markClosed();
        }
        pools.clear();
    }

    private static void closePool(DataSource dataSource) {
        if (dataSource instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception ignored) {
                // The generation is already unreachable; a failed close is surfaced by its pool.
            }
        }
    }
}
