package local.ptmcp.datasource;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import javax.sql.DataSource;

/**
 * Owns at most one active pool per datasource. A changed identity builds a new generation and
 * drains the previous one; a credential never becomes part of a key or an identifier.
 */
public final class DataSourcePoolRegistry implements AutoCloseable {
    private final PoolFactory poolFactory;
    private final Map<Long, PoolHandle> pools = new HashMap<>();
    private final Set<PoolHandle> retired = new HashSet<>();
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
        retired.removeIf(handle -> handle.state() == PoolHandle.State.CLOSED);
        PoolHandle current = pools.get(key.datasourceId());
        if (current != null && current.key().equals(key)) {
            return current;
        }
        if (current != null && key.configVersion() <= current.key().configVersion()) {
            throw new DomainException(ErrorCode.SCHEMA_STALE);
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
            retainDraining(current);
        }
        pools.put(key.datasourceId(), next);
        return next;
    }

    public synchronized Optional<PoolHandle> current(long datasourceId) {
        retired.removeIf(handle -> handle.state() == PoolHandle.State.CLOSED);
        return Optional.ofNullable(pools.get(datasourceId));
    }

    /** Safe aggregate diagnostic; no endpoints, credentials or driver errors are exposed. */
    public synchronized long failedDisposals() {
        return retired.stream().filter(PoolHandle::disposalFailed).count();
    }

    /** The registry keeps ownership of uncertain disposals for explicit retry passes. */
    public synchronized void retryDisposals() {
        for (PoolHandle handle : retired) {
            handle.retryDisposal();
        }
        retired.removeIf(handle -> handle.state() == PoolHandle.State.CLOSED);
    }

    private void retainDraining(PoolHandle handle) {
        if (handle.state() != PoolHandle.State.CLOSED) {
            retired.add(handle);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            retryDisposals();
            return;
        }
        closed = true;
        for (PoolHandle handle : pools.values()) {
            handle.beginDrain();
            retainDraining(handle);
        }
        pools.clear();
    }
}
