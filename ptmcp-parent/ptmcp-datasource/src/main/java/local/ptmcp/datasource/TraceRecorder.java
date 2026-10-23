package local.ptmcp.datasource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class TraceRecorder {
    private final List<ExecutionTrace.Entry> entries = new ArrayList<>();
    private long lastNanos = System.nanoTime();
    private long poolGeneration;

    void poolGeneration(long generation) {
        this.poolGeneration = generation;
    }

    void record(ExecutionStage stage, ExecutionState state) {
        long now = System.nanoTime();
        entries.add(
                new ExecutionTrace.Entry(
                        stage, state, TimeUnit.NANOSECONDS.toMillis(now - lastNanos)));
        lastNanos = now;
    }

    ExecutionState lastState() {
        return entries.isEmpty() ? null : entries.get(entries.size() - 1).state();
    }

    ExecutionTrace finish(ExecutionState finalState) {
        return new ExecutionTrace(poolGeneration, entries, finalState);
    }
}
