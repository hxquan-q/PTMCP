package local.ptmcp.guard.scope;

import java.util.List;
import java.util.Objects;

/** The ranges, output columns and resolved references of one query block. */
public record ScopeColumns(
        int scopeId,
        Integer parentScopeId,
        List<RangeBinding> ranges,
        List<OutputColumn> outputs,
        List<ColumnBinding> bindings) {
    public ScopeColumns {
        if (scopeId <= 0) {
            throw new IllegalArgumentException("scopeId must be positive");
        }
        ranges = List.copyOf(Objects.requireNonNull(ranges, "ranges"));
        outputs = List.copyOf(Objects.requireNonNull(outputs, "outputs"));
        bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
    }
}
