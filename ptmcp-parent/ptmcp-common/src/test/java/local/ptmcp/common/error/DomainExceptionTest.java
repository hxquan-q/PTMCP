package local.ptmcp.common.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DomainExceptionTest {
    @ParameterizedTest
    @EnumSource(ErrorCode.class)
    void carriesTheStableCodeWithoutRawDetailsOrInfrastructureCauses(ErrorCode code) {
        DomainException error = new DomainException(code);

        assertSame(code, error.errorCode());
        assertEquals(code.name(), error.getMessage());
        assertNull(error.getCause());
    }

    @Test
    void rejectsAnAbsentErrorCodeAtConstruction() {
        assertThrows(NullPointerException.class, () -> new DomainException(null));
    }
}
