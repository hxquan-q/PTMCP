package local.ptmcp.persistence.security;

/**
 * Execution admission quotas: per user, per datasource, gateway-wide, and the bounded queue that
 * holds requests while they wait for a datasource slot. Implementations must hand out permits
 * atomically (no oversell) and release every partially acquired permit when acquisition fails
 * mid-way. Queue permits expire: a queue wait beyond the window turns into gateway-busy, never into
 * an execution.
 */
public interface QuotaLimiter {
    int USER_MAX = 3;
    int DATASOURCE_MAX = 8;
    int GATEWAY_MAX = 16;
    int QUEUED_MAX = 32;
    long QUEUE_WINDOW_NANOS = 3_000_000_000L;

    QueuePermit acquireQueue(long userId);

    ExecutionPermit acquireExecution(long userId, long datasourceId, QueuePermit queuedAt);

    void release(ExecutionPermit permit);

    void release(QueuePermit permit);

    record QueuePermit(long userId, long expiresAtNanos) {}

    record ExecutionPermit(long userId, long datasourceId) {}
}
