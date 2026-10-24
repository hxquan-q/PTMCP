package local.ptmcp.guard.approved;

import local.ptmcp.guard.budget.RelationBudget;
import local.ptmcp.guard.lexing.SqlLexicalPolicy;
import local.ptmcp.guard.rewrite.LimitRewriter;

/**
 * Immutable guard configuration for one request: dialect and lexical modes, the request's row
 * budget (may only tighten the outer limit) and the relation-reference budget. Values come from
 * verified server configuration and the caller's request budget; nothing here is derived from the
 * SQL text itself.
 */
public record GuardPolicy(
        SqlLexicalPolicy lexicalPolicy,
        int requestedMaxRows,
        int maxRelationReferences,
        String guardVersion) {
    public static final String CURRENT_GUARD_VERSION = "1";

    public GuardPolicy {
        if (lexicalPolicy == null || guardVersion == null || guardVersion.isEmpty()) {
            throw new IllegalArgumentException("lexical policy and guard version are required");
        }
        if (requestedMaxRows < 1 || requestedMaxRows > LimitRewriter.MAX_ROWS) {
            throw new IllegalArgumentException(
                    "requestedMaxRows must be between 1 and " + LimitRewriter.MAX_ROWS);
        }
        if (maxRelationReferences < 0
                || maxRelationReferences > RelationBudget.MAX_RELATION_REFERENCES) {
            throw new IllegalArgumentException(
                    "maxRelationReferences must be between 0 and "
                            + RelationBudget.MAX_RELATION_REFERENCES);
        }
    }

    public static GuardPolicy mysql(
            boolean ansiQuotes, boolean noBackslashEscapes, int requestedMaxRows) {
        return new GuardPolicy(
                SqlLexicalPolicy.mysql(ansiQuotes, noBackslashEscapes),
                requestedMaxRows,
                RelationBudget.MAX_RELATION_REFERENCES,
                CURRENT_GUARD_VERSION);
    }

    public static GuardPolicy postgresql(boolean standardConformingStrings, int requestedMaxRows) {
        return new GuardPolicy(
                SqlLexicalPolicy.postgresql(standardConformingStrings),
                requestedMaxRows,
                RelationBudget.MAX_RELATION_REFERENCES,
                CURRENT_GUARD_VERSION);
    }
}
