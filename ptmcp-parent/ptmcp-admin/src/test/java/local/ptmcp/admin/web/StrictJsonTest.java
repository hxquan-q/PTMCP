package local.ptmcp.admin.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

class StrictJsonTest {
    private record Probe(String name, long id) {}

    @Test
    void acceptsNumericIdentifiersAsJsonStrings() {
        Probe probe =
                StrictJson.parse(
                        "{\"name\":\"orders\",\"id\":\"9223372036854775807\"}"
                                .getBytes(StandardCharsets.UTF_8),
                        Probe.class);

        assertEquals("orders", probe.name());
        assertEquals(Long.MAX_VALUE, probe.id());
    }

    @Test
    void acceptsUtf8Payload() {
        Probe probe =
                StrictJson.parse(
                        "{\"name\":\"销售订单\",\"id\":\"1\"}".getBytes(StandardCharsets.UTF_8),
                        Probe.class);

        assertEquals("销售订单", probe.name());
    }

    @Test
    void rejectsUnknownFields() {
        assertCode(ErrorCode.INVALID_ARGUMENT, "{\"name\":\"orders\",\"id\":\"1\",\"extra\":true}");
    }

    @Test
    void rejectsDuplicateKeys() {
        assertCode(ErrorCode.INVALID_ARGUMENT, "{\"name\":\"orders\",\"id\":\"1\",\"id\":\"2\"}");
    }

    @Test
    void rejectsMalformedUtf8() {
        byte[] malformed = {'{', '"', 'n', 'a', 'm', 'e', '"', ':', '"', (byte) 0xC3, '"', '}'};

        DomainException error =
                assertThrows(DomainException.class, () -> StrictJson.parse(malformed, Probe.class));

        assertEquals(ErrorCode.INVALID_ARGUMENT, error.errorCode());
    }

    @Test
    void rejectsMalformedJsonWithoutEchoingTheBody() {
        DomainException error =
                assertThrows(
                        DomainException.class,
                        () ->
                                StrictJson.parse(
                                        "{\"name\":".getBytes(StandardCharsets.UTF_8),
                                        Probe.class));

        assertEquals(ErrorCode.INVALID_ARGUMENT, error.errorCode());
        assertEquals(ErrorCode.INVALID_ARGUMENT.name(), error.getMessage());
    }

    @Test
    void rejectsBodiesAtTheLimitPlusOneByte() {
        byte[] oversized = new byte[StrictJson.MAX_BODY_BYTES + 1];

        DomainException error =
                assertThrows(DomainException.class, () -> StrictJson.parse(oversized, Probe.class));

        assertEquals(ErrorCode.METADATA_TOO_LARGE, error.errorCode());
    }

    @Test
    void acceptsBodiesAtTheLimitAndRejectsMissingInput() {
        byte[] exactlyLimit = new byte[StrictJson.MAX_BODY_BYTES];

        assertThrows(DomainException.class, () -> StrictJson.parse(exactlyLimit, Probe.class));
        assertEquals(
                ErrorCode.INVALID_ARGUMENT,
                assertThrows(DomainException.class, () -> StrictJson.parse(null, Probe.class))
                        .errorCode());
    }

    private static void assertCode(ErrorCode expected, String json) {
        DomainException error =
                assertThrows(
                        DomainException.class,
                        () -> StrictJson.parse(json.getBytes(StandardCharsets.UTF_8), Probe.class));

        assertEquals(expected, error.errorCode());
        assertEquals(expected.name(), error.getMessage());
    }
}
