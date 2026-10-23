package local.ptmcp.datasource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Bounded tabular result. Cell values are strings; SQL NULL stays null. */
public record ReadOnlyResult(
        List<Column> columns, List<List<String>> rows, boolean limitReached, ExecutionTrace trace) {
    public record Column(String label, String typeName, int jdbcType) {
        public Column {
            if (label == null || typeName == null) {
                throw new IllegalArgumentException("column label and type name are required");
            }
        }
    }

    public ReadOnlyResult {
        columns = List.copyOf(columns);
        List<List<String>> copy = new ArrayList<>(rows.size());
        for (List<String> row : rows) {
            copy.add(Collections.unmodifiableList(new ArrayList<>(row)));
        }
        rows = Collections.unmodifiableList(copy);
    }
}
