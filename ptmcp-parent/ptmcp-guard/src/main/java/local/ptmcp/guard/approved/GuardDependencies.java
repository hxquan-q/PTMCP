package local.ptmcp.guard.approved;

import local.ptmcp.guard.budget.BoundedParser;
import local.ptmcp.guard.scope.RelationColumns;
import local.ptmcp.guard.scope.ScopeTableBinder;

import java.time.Clock;
import java.util.Objects;

/**
 * Server-side collaborators for a guard run: the bounded parser, the published-snapshot relation
 * and column lookups, the read-permission callback and the clock used to stamp approval validity.
 * Every component must be backed by server state; the pipeline never opens a business connection
 * and never consults user input to build these.
 */
public record GuardDependencies(
        BoundedParser parser,
        ScopeTableBinder.RelationCatalog relations,
        RelationColumns columns,
        PermissionDecider permissions,
        Clock clock) {
    public GuardDependencies {
        Objects.requireNonNull(parser, "parser");
        Objects.requireNonNull(relations, "relations");
        Objects.requireNonNull(columns, "columns");
        Objects.requireNonNull(permissions, "permissions");
        Objects.requireNonNull(clock, "clock");
    }
}
