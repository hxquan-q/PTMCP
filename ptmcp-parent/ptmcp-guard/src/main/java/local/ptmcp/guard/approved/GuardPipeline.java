package local.ptmcp.guard.approved;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.common.resource.ResourceNamespace;
import local.ptmcp.common.resource.ResourceRef;
import local.ptmcp.guard.ast.SqlAllowlistGate;
import local.ptmcp.guard.ast.SqlStatementGate;
import local.ptmcp.guard.budget.RelationBudget;
import local.ptmcp.guard.lexing.LexicalPrecheck;
import local.ptmcp.guard.rewrite.FinalSqlValidator;
import local.ptmcp.guard.rewrite.LimitRewriter;
import local.ptmcp.guard.rewrite.QualifiedRewriter;
import local.ptmcp.guard.rewrite.RewrittenSql;
import local.ptmcp.guard.scope.ColumnBinder;
import local.ptmcp.guard.scope.ColumnBindingResult;
import local.ptmcp.guard.scope.ScopeTableBinder;

import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.statement.select.Select;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The complete guard pipeline from raw request SQL to a package-private {@link ApprovedQuery}.
 *
 * <p>Fixed order: lexical precheck → statement shape → expression allowlist → bounded parse → table
 * binding → relation budget → column binding → physical READ authorization (callback per bound
 * relation) → outer limit tightening → qualification rewrite → re-parse and plan-compare
 * validation. Any static rejection propagates its {@link DomainException} and later stages never
 * run; a rejection never borrows a business connection and never produces an {@code ApprovedQuery}.
 *
 * <p>The pipeline proves only the static plan: authorization freshness, snapshot currency and
 * subject consistency remain the executor's pre-checkout/pre-commit duty.
 */
public final class GuardPipeline {
    private GuardPipeline() {}

    public static ApprovedQuery guard(
            String sql, GuardContext context, GuardPolicy policy, GuardDependencies deps) {
        Objects.requireNonNull(sql, "sql");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(deps, "deps");

        LexicalPrecheck.check(sql, policy.lexicalPolicy());
        SqlStatementGate.check(sql);
        SqlAllowlistGate.check(sql);

        Select select = deps.parser().parse(sql);

        ResourceNamespace namespace =
                new ResourceNamespace(
                        context.datasourceId(),
                        policy.lexicalPolicy().engine(),
                        context.catalog(),
                        context.schema());

        ScopeTableBinder.BindingResult bound =
                ScopeTableBinder.bind(select, namespace, deps.relations());
        if (!bound.unresolved().isEmpty()) {
            throw new DomainException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        RelationBudget.requireWithin(bound, policy.maxRelationReferences());
        ColumnBindingResult bindings = ColumnBinder.bind(select, bound, deps.columns());

        // First denial stops the run before any rewrite or approval text exists.
        for (ResourceRef resource : bound.physicalRefs()) {
            if (!deps.permissions().canRead(context, resource)) {
                throw new DomainException(ErrorCode.TABLE_NOT_AUTHORIZED);
            }
        }

        LimitRewriter.rewrite(select, policy.requestedMaxRows());
        int effectiveLimit = outerLimitConstant(select);
        RewrittenSql rewritten = QualifiedRewriter.rewrite(select, bindings);
        FinalSqlValidator.validate(
                rewritten.text(),
                rewritten.plan(),
                namespace,
                deps.relations(),
                deps.columns(),
                effectiveLimit);

        Instant approvedAt = deps.clock().instant();
        return new ApprovedQuery(
                UUID.randomUUID(),
                context,
                bound.physicalRefs(),
                rewritten.text(),
                sqlHash(rewritten.text()),
                effectiveLimit,
                policy.guardVersion(),
                approvedAt);
    }

    private static int outerLimitConstant(Select select) {
        if (select.getLimit() == null
                || !(select.getLimit().getRowCount() instanceof LongValue constant)) {
            throw new DomainException(ErrorCode.REWRITE_VALIDATION_FAILED);
        }
        if (constant.getValue() < 1) {
            throw new DomainException(ErrorCode.SQL_UNSUPPORTED);
        }
        return Math.toIntExact(constant.getValue());
    }

    private static byte[] sqlHash(String finalSql) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
        return digest.digest(finalSql.getBytes(StandardCharsets.UTF_8));
    }
}
