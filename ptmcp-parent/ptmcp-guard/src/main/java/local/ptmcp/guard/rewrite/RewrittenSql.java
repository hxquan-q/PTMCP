package local.ptmcp.guard.rewrite;

import java.util.Objects;

/**
 * Rendered rewrite output. The text is derived from the parsed AST and the accompanying plan; it is
 * still NOT an approved query and must be re-parsed and re-validated by {@link FinalSqlValidator}
 * before any execution boundary may accept it.
 */
public record RewrittenSql(String text, RewritePlan plan) {
    public RewrittenSql {
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("rewritten text is required");
        }
        Objects.requireNonNull(plan, "plan");
    }
}
