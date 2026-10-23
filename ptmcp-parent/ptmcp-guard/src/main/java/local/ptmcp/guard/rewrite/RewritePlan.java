package local.ptmcp.guard.rewrite;

import local.ptmcp.common.resource.ResourceRef;
import local.ptmcp.guard.scope.ColumnBinder;
import local.ptmcp.guard.scope.ColumnBinding;
import local.ptmcp.guard.scope.ColumnBindingResult;
import local.ptmcp.guard.scope.OutputColumn;
import local.ptmcp.guard.scope.RangeBinding;
import local.ptmcp.guard.scope.ScopeColumns;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The facts a rewritten statement must still satisfy after re-parsing. Physical range aliases are
 * intentionally absent: qualification may keep a user alias or generate an internal one, while the
 * relation and column provenance stay unchanged. Non-physical ranges keep their alias because CTE
 * and derived names are never rewritten.
 *
 * <p>This record is evidence for comparison only; it authorizes nothing.
 */
public record RewritePlan(
        List<ResourceRef> physicalRelations, List<BoundColumn> columns, List<String> outputNames) {
    public RewritePlan {
        physicalRelations =
                List.copyOf(Objects.requireNonNull(physicalRelations, "physicalRelations"));
        columns = List.copyOf(Objects.requireNonNull(columns, "columns"));
        outputNames = List.copyOf(Objects.requireNonNull(outputNames, "outputNames"));
    }

    /** A resolved column usage with physical range aliases normalised away. */
    public record BoundColumn(
            ColumnBinder.RangeKind kind,
            ResourceRef resource,
            String columnName,
            int ordinal,
            String rangeAlias) {
        public BoundColumn {
            Objects.requireNonNull(kind, "kind");
            if (columnName == null || columnName.isEmpty() || ordinal < 0) {
                throw new IllegalArgumentException("column name and ordinal are required");
            }
            if (kind == ColumnBinder.RangeKind.PHYSICAL) {
                if (resource == null || rangeAlias != null) {
                    throw new IllegalArgumentException("physical binding needs a resource only");
                }
            } else if (resource != null || rangeAlias == null || rangeAlias.isEmpty()) {
                throw new IllegalArgumentException("non-physical binding needs its range alias");
            }
        }

        static BoundColumn from(ColumnBinding binding) {
            boolean physical = binding.kind() == ColumnBinder.RangeKind.PHYSICAL;
            return new BoundColumn(
                    binding.kind(),
                    binding.resource(),
                    binding.columnName(),
                    binding.ordinal(),
                    physical ? null : binding.rangeAlias());
        }
    }

    public static RewritePlan from(ColumnBindingResult bindings) {
        Objects.requireNonNull(bindings, "bindings");
        List<BoundColumn> columns = bindings.bindings().stream().map(BoundColumn::from).toList();
        List<String> outputs =
                bindings.primaryScope().outputs().stream().map(OutputColumn::name).toList();
        return new RewritePlan(physicalRelations(bindings), columns, outputs);
    }

    static List<ResourceRef> physicalRelations(ColumnBindingResult bindings) {
        List<ResourceRef> relations = new ArrayList<>();
        for (ScopeColumns scope : bindings.scopes()) {
            for (RangeBinding range : scope.ranges()) {
                if (range.kind() == ColumnBinder.RangeKind.PHYSICAL) {
                    relations.add(range.resource());
                }
            }
        }
        return List.copyOf(relations);
    }
}
