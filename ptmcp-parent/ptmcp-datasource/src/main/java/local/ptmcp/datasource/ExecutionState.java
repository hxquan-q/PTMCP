package local.ptmcp.datasource;

/** Connection lifecycle states from the execution protocol. */
public enum ExecutionState {
    ACQUIRED,
    CONFIGURED,
    EXECUTING,
    DRAINING,
    CLEANING,
    CANCELLING,
    RETURNED,
    EVICTED
}
