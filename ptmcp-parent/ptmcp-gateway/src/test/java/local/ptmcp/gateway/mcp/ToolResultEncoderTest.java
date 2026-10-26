package local.ptmcp.gateway.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import local.ptmcp.datasource.ExecutionState;
import local.ptmcp.datasource.ExecutionTrace;
import local.ptmcp.datasource.postgres.PgReadOnlyResult;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/** The PostgreSQL projection preserves typed cells and executor truncation metadata. */
class ToolResultEncoderTest {
    @Test
    void pgResultKeepsTypesAndTruncationMetadata() {
        PgReadOnlyResult result =
                new PgReadOnlyResult(
                        List.of(
                                new PgReadOnlyResult.Column(
                                        0,
                                        "id",
                                        "int4",
                                        PgReadOnlyResult.LogicalType.INTEGER,
                                        null,
                                        false,
                                        4),
                                new PgReadOnlyResult.Column(
                                        1,
                                        "payload",
                                        "text",
                                        PgReadOnlyResult.LogicalType.STRING,
                                        null,
                                        true,
                                        12)),
                        List.of(List.of(7, "abc"), List.of(8, "def")),
                        List.of(new PgReadOnlyResult.CellTruncation(1, 1, 9000, 4096)),
                        false,
                        true,
                        new ExecutionTrace(3, List.of(), ExecutionState.RETURNED));

        ToolResultEncoder.Encoded encoded = ToolResultEncoder.encode(result);

        assertEquals(2, encoded.columns().size());
        Map<String, Object> id = encoded.columns().get(0);
        assertEquals("INTEGER", id.get("logicalType"));
        assertNull(id.get("encoding"));
        assertFalse((Boolean) id.get("nullable"));
        assertEquals(7, encoded.rows().get(0).get(0));
        assertEquals("abc", encoded.rows().get(0).get(1));
        assertEquals("CELL_LIMIT", encoded.cellTruncations().get(0).get("reason"));
        assertEquals(9000, encoded.cellTruncations().get(0).get("originalBytes"));
        assertEquals(4096, encoded.cellTruncations().get(0).get("returnedBytes"));
        assertFalse(encoded.rowLimitReached());
        assertTrue(encoded.responseLimitReached());
    }
}
