package local.ptmcp.guard.budget;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import net.sf.jsqlparser.parser.Node;
import net.sf.jsqlparser.statement.select.Select;

/**
 * Computes the structural depth of a parsed query from its public AST node tree.
 *
 * <p>The walk counts each distinct AST object once; parser wrapper nodes that repeat their child's
 * value do not add a level. This makes a flat {@code SELECT 1} three levels deep and each
 * parenthesis, function argument, CASE branch, UNION branch or subquery add an explicit level.
 * Nodes are never inspected through parser-internal fields.
 */
public final class AstMetrics {
    private AstMetrics() {}

    public static int maxDepth(Select select) {
        if (select == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        Node root = select.getASTNode();
        if (root == null) {
            // Parsed input always carries a public AST tree. Fail closed for hand-built nodes.
            throw new DomainException(ErrorCode.SQL_BUDGET_EXCEEDED);
        }
        return depth(root, null);
    }

    public static void requireWithinDepth(Select select, int maximumDepth) {
        if (maximumDepth < 1) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (maxDepth(select) > maximumDepth) {
            throw new DomainException(ErrorCode.SQL_BUDGET_EXCEEDED);
        }
    }

    private static int depth(Node node, Object parentValue) {
        Object value = node.jjtGetValue();
        int level = value != null && value != parentValue ? 1 : 0;
        int deepestChild = 0;
        for (int index = 0; index < node.jjtGetNumChildren(); index++) {
            deepestChild = Math.max(deepestChild, depth(node.jjtGetChild(index), value));
        }
        return level + deepestChild;
    }
}
