package local.ptmcp.common.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

class ErrorCodeTest {
    @Test
    void compiledCodesRemainUniqueSixDigitBusinessNumbersNotHttpStatusCodes() {
        Set<Integer> allocated = new HashSet<>();
        for (ErrorCode code : ErrorCode.values()) {
            assertTrue(code.restCode() >= 100000 && code.restCode() <= 999999);
            assertNotEquals(200, code.restCode());
            assertTrue(allocated.add(code.restCode()), () -> "Duplicate error number: " + code);
        }
    }

    @Test
    void existingPublicNumbersAndSymbolsKeepTheirMeaning() {
        assertEquals(100001, ErrorCode.INVALID_ARGUMENT.restCode());
        assertEquals(100006, ErrorCode.VERSION_CONFLICT.restCode());
        assertEquals(150008, ErrorCode.REWRITE_VALIDATION_FAILED.restCode());
        assertEquals("TABLE_NOT_AUTHORIZED", ErrorCode.TABLE_NOT_AUTHORIZED.name());
    }

    @Test
    void previouslyMissingApiSymbolsHaveExplicitNewAllocations() {
        assertEquals(110008, ErrorCode.AUTHENTICATION_FAILED.restCode());
        assertEquals(130007, ErrorCode.CONNECTION_BUDGET_EXCEEDED.restCode());
        assertEquals(140005, ErrorCode.SELF_PRIVILEGE_ESCALATION.restCode());
    }
}
