package local.ptmcp.guard.scope;

import java.util.List;
import java.util.Objects;

/**
 * Immutable column-binding result. {@code primaryScope} is the outermost query body; nested CTE,
 * derived and subquery scopes are included in encounter order.
 */
public record ColumnBindingResult(
        ScopeColumns primaryScope, List<ScopeColumns> scopes, List<ColumnBinding> bindings) {
    public ColumnBindingResult {
        Objects.requireNonNull(primaryScope, "primaryScope");
        scopes = List.copyOf(Objects.requireNonNull(scopes, "scopes"));
        bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
    }
}
