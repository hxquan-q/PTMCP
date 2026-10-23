package local.ptmcp.guard.scope;

import local.ptmcp.common.resource.ResourceRef;

import java.util.Objects;

/**
 * A resolved column reference. The alias/kind/resource describe the range used at this site; CTE
 * and derived references repeat their physical provenance through {@link OutputColumn#sources()}.
 */
public record ColumnBinding(
        String rangeAlias,
        ColumnBinder.RangeKind kind,
        ResourceRef resource,
        String columnName,
        int ordinal) {
    public ColumnBinding {
        if (rangeAlias == null || rangeAlias.isEmpty()) {
            throw new IllegalArgumentException("range alias is required");
        }
        Objects.requireNonNull(kind, "kind");
        if (columnName == null || columnName.isEmpty()) {
            throw new IllegalArgumentException("column name is required");
        }
        if (ordinal < 0) {
            throw new IllegalArgumentException("ordinal must not be negative");
        }
        if (kind == ColumnBinder.RangeKind.PHYSICAL && resource == null) {
            throw new IllegalArgumentException("physical binding needs a resource");
        }
        if (kind != ColumnBinder.RangeKind.PHYSICAL && resource != null) {
            throw new IllegalArgumentException("non-physical binding cannot carry a resource");
        }
    }
}
