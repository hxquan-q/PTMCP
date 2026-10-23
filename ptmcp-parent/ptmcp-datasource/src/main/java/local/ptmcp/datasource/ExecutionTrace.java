package local.ptmcp.datasource;

import java.util.List;

/**
 * Observable lifecycle evidence. Durations are monotonic-clock milliseconds per stage; the trace
 * carries no SQL, credential, host or username.
 */
public record ExecutionTrace(long poolGeneration, List<Entry> entries, ExecutionState finalState) {
    public record Entry(ExecutionStage stage, ExecutionState state, long durationMillis) {
        public Entry {
            if (stage == null || state == null) {
                throw new IllegalArgumentException("stage and state are required");
            }
            if (durationMillis < 0) {
                throw new IllegalArgumentException("durationMillis must not be negative");
            }
        }
    }

    public ExecutionTrace {
        entries = List.copyOf(entries);
        if (finalState == null) {
            throw new IllegalArgumentException("finalState is required");
        }
    }

    public List<ExecutionState> states() {
        return entries.stream().map(Entry::state).toList();
    }

    public List<ExecutionStage> stages() {
        return entries.stream().map(Entry::stage).toList();
    }
}
