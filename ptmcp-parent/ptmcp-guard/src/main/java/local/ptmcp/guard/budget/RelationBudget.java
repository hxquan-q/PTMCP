package local.ptmcp.guard.budget;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.common.resource.ResourceRef;
import local.ptmcp.guard.scope.ScopeTableBinder;

import java.util.List;

/**
 * Enforces the relation-access budget on the binder's physical references.
 *
 * <p>Repeated accesses are counted, not deduplicated: opening the same table twice is two physical
 * relation references. Unresolved names are the caller's rejection decision and are not counted
 * here.
 */
public final class RelationBudget {
    public static final int MAX_RELATION_REFERENCES = 64;

    private RelationBudget() {}

    public static int count(List<ResourceRef> physicalRefs) {
        return physicalRefs == null ? 0 : physicalRefs.size();
    }

    public static void requireWithin(List<ResourceRef> physicalRefs, int maximumReferences) {
        if (maximumReferences < 0) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (count(physicalRefs) > maximumReferences) {
            throw new DomainException(ErrorCode.SQL_BUDGET_EXCEEDED);
        }
    }

    public static void requireWithin(
            ScopeTableBinder.BindingResult binding, int maximumReferences) {
        if (binding == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        requireWithin(binding.physicalRefs(), maximumReferences);
    }
}
