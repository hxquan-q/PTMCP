package local.ptmcp.gateway.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

class SchemaContextTextTest {
    @Test
    void aggregateBudgetPreservesUnicodeAndExposesOmittedText() {
        SchemaContextText context = new SchemaContextText();
        String input = "😀元\n\"\\".repeat(500);
        int total = 0;
        for (int index = 0; index < 40; index++) {
            SchemaContextText.Text text = context.take(input);
            byte[] bytes = text.value().getBytes(StandardCharsets.UTF_8);
            assertTrue(bytes.length <= 2048);
            assertEquals(text.value(), new String(bytes, StandardCharsets.UTF_8));
            assertFalse(text.value().contains("�"));
            assertTrue(text.truncated());
            total += bytes.length;
        }
        assertTrue(total <= 32768);
        assertTrue(total >= 32764);
        assertTrue(context.truncated());
        assertEquals("", context.take("more").value());
    }

    @Test
    void exactBoundaryNullAndUpstreamTruncationHaveDistinctSignals() {
        SchemaContextText context = new SchemaContextText();
        assertNull(context.take(null).value());
        assertFalse(context.take("").truncated());
        assertFalse(context.take("😀".repeat(512)).truncated());
        assertFalse(context.truncated());
        assertTrue(context.take("already bounded", true).truncated());
        assertTrue(context.truncated());
    }
}
