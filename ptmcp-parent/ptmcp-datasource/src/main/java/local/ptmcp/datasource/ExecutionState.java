package local.ptmcp.datasource;

/** Connection lifecycle states from the execution protocol. */
public enum ExecutionState {
    QUEUED,
    CONNECTED,
    ACQUIRED,
    CONFIGURED,
    EXECUTING,
    READING,
    DRAINING,
    CLEANING,
    CANCELLING,
    RETURNED,
    QUARANTINED,
    EVICTED
}
