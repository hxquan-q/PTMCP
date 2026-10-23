package local.ptmcp.datasource;

/** Stable stage names for metrics; they never contain SQL text. */
public enum ExecutionStage {
    CHECKOUT,
    CONFIGURE,
    EXECUTE,
    DRAIN,
    CANCEL,
    CLEAN,
    RETURN,
    EVICT
}
