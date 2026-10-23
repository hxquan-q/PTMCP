package local.ptmcp.guard.scope;

import local.ptmcp.common.resource.ResourceRef;

import java.util.List;
import java.util.Objects;

/**
 * One range visible in a query block. Physical ranges carry their resolved resource; CTE and
 * derived ranges keep only output columns and the physical sources they were projected from.
 */
public record RangeBinding(
        String alias,
        ColumnBinder.RangeKind kind,
        ResourceRef resource,
        List<ColumnDef> columns,
        List<ColumnBinding> sources) {
    public RangeBinding {
        if (alias == null || alias.isEmpty()) {
            throw new IllegalArgumentException("range alias is required");
        }
        Objects.requireNonNull(kind, "kind");
        columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
        sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
        if (columns.size() != sources.size()) {
            throw new IllegalArgumentException("each output column needs a source binding");
        }
        if (kind == ColumnBinder.RangeKind.PHYSICAL && resource == null) {
            throw new IllegalArgumentException("physical range needs a resource");
        }
        if (kind != ColumnBinder.RangeKind.PHYSICAL && resource != null) {
            throw new IllegalArgumentException("non-physical range cannot carry a resource");
        }
    }
}
