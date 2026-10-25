package local.ptmcp.datasource;

/** Stable stage names for metrics; they never contain SQL text. */
public enum ExecutionStage {
    QUEUE,
    CONNECT,
    CHECKOUT,
    CONFIGURE,
    EXECUTE,
    READ,
    DRAIN,
    CANCEL,
    CLEAN,
    RETURN,
    EVICT
}
