package local.ptmcp.guard.budget;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;
import local.ptmcp.common.resource.DatabaseEngine;
import local.ptmcp.common.resource.ResourceId;
import local.ptmcp.common.resource.ResourceKey;
import local.ptmcp.common.resource.ResourceNamespace;
import local.ptmcp.common.resource.ResourceRef;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class RelationBudgetTest {
    private static final ResourceNamespace NAMESPACE =
            new ResourceNamespace(new ResourceId(1), DatabaseEngine.MYSQL, "erp", "");

    @Test
    void acceptsExactlySixtyFourReferencesAndRejectsSixtyFive() {
        List<ResourceRef> sixtyFour = refs(64);
        List<ResourceRef> sixtyFive = refs(65);

        assertDoesNotThrow(() -> RelationBudget.requireWithin(sixtyFour, 64));
        assertEquals(64, RelationBudget.count(sixtyFour));

        DomainException error =
                assertThrows(
                        DomainException.class, () -> RelationBudget.requireWithin(sixtyFive, 64));
        assertEquals(ErrorCode.SQL_BUDGET_EXCEEDED, error.errorCode());
    }

    @Test
    void repeatedPhysicalAccessesCountTowardsTheBudget() {
        ResourceRef single = refs(1).get(0);
        List<ResourceRef> repeated = new ArrayList<>();
        for (int index = 0; index < 65; index++) {
            repeated.add(single);
        }

        DomainException error =
                assertThrows(
                        DomainException.class, () -> RelationBudget.requireWithin(repeated, 64));
        assertEquals(ErrorCode.SQL_BUDGET_EXCEEDED, error.errorCode());
    }

    private static List<ResourceRef> refs(int size) {
        List<ResourceRef> refs = new ArrayList<>();
        for (int index = 0; index < size; index++) {
            ResourceKey key = new ResourceKey(NAMESPACE, "t" + index);
            refs.add(new ResourceRef(key, new ResourceId(index + 1L), 1, 1, 1));
        }
        return refs;
    }
}
