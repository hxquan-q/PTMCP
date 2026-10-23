package local.ptmcp.common.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ResourceIdTest {
    @ParameterizedTest
    @ValueSource(strings = {"1", "9007199254740993", "9223372036854775807"})
    void preservesTheFullSignedBigintRangeWithoutFloatingPoint(String value) {
        ResourceId id = ResourceId.parse(value);

        assertEquals(value, id.toString());
        assertEquals(Long.parseLong(value), id.value());
    }

    @Test
    void canonicalizesLeadingZerosWithoutChangingIdentity() {
        ResourceId id = ResourceId.parse("0009007199254740993");

        assertEquals(new ResourceId(9007199254740993L), id);
        assertEquals("9007199254740993", id.toString());
        assertEquals(ResourceId.parse("1").hashCode(), ResourceId.parse("001").hashCode());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(
            strings = {
                "0",
                "000",
                "-1",
                "+1",
                " 1",
                "1 ",
                "1\n",
                "1.0",
                "1e3",
                "0x10",
                "١",
                "１",
                "1_000",
                "9223372036854775808",
                "18446744073709551615"
            })
    void rejectsNonIdsAndOutOfRangeInputWithoutEchoingInput(String value) {
        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> ResourceId.parse(value));

        assertEquals(
                "Resource ID must be a positive signed BIGINT in ASCII decimal form",
                error.getMessage());
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void directConstructionCannotBypassThePositiveIdInvariant(long value) {
        assertThrows(IllegalArgumentException.class, () -> new ResourceId(value));
    }
}
