package local.ptmcp.guard.scope;

import java.util.List;
import java.util.Objects;

/** One projected column, addressed by its position so duplicate names remain distinguishable. */
public record OutputColumn(int ordinal, String name, List<ColumnBinding> sources) {
    public OutputColumn {
        if (ordinal < 0) {
            throw new IllegalArgumentException("ordinal must not be negative");
        }
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("output name is required");
        }
        sources = List.copyOf(Objects.requireNonNull(sources, "sources"));
    }
}
