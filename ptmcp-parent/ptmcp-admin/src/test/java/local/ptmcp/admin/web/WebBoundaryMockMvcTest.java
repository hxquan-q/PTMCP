package local.ptmcp.admin.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.http.HttpServletRequest;

import local.ptmcp.common.error.DomainException;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Protocol-shape coverage for the HTTP boundary primitives, not a business endpoint. */
class WebBoundaryMockMvcTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(new ProbeController())
                    .setControllerAdvice(new ProbeErrors())
                    .addFilters(new RequestIdFilter())
                    .build();

    @Test
    void serverRequestIdWinsOverTheClientHeader() throws Exception {
        MvcResult result =
                mvc.perform(
                                MockMvcRequestBuilders.post(ProbeController.PATH)
                                        .header(RequestIdFilter.HEADER, "client-supplied")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(validBody()))
                        .andReturn();

        assertEquals(200, result.getResponse().getStatus());
        String requestId = result.getResponse().getHeader(RequestIdFilter.HEADER);
        assertNotNull(requestId);
        assertNotEquals("client-supplied", requestId);
        UUID.fromString(requestId);
    }

    @Test
    void unknownFieldReturns400WithoutSensitiveDetails() throws Exception {
        JsonNode body =
                assertError(
                        "{\"name\":\"orders\",\"id\":\"1\",\"extra\":true}",
                        400,
                        "INVALID_ARGUMENT");

        assertEquals(Set.of("status", "errorCode", "retryable", "requestId"), fieldNames(body));
    }

    @Test
    void duplicateKeyReturns400() throws Exception {
        assertError("{\"name\":\"orders\",\"id\":\"1\",\"id\":\"2\"}", 400, "INVALID_ARGUMENT");
    }

    @Test
    void malformedUtf8Returns400() throws Exception {
        byte[] malformed = {'{', '"', 'n', 'a', 'm', 'e', '"', ':', '"', (byte) 0xC3, '"', '}'};

        JsonNode body = perform(malformed);
        assertEquals(400, body.get("status").asInt());
        assertEquals("INVALID_ARGUMENT", body.get("errorCode").asString());
    }

    @Test
    void oversizedBodyReturns413() throws Exception {
        byte[] oversized = new byte[StrictJson.MAX_BODY_BYTES + 1];
        java.util.Arrays.fill(oversized, (byte) ' ');

        JsonNode body = perform(oversized);
        assertEquals(413, body.get("status").asInt());
        assertEquals("METADATA_TOO_LARGE", body.get("errorCode").asString());
        assertFalse(body.get("retryable").asBoolean());
    }

    @Test
    void acceptedRequestEchoesTheParsedNumericId() throws Exception {
        MvcResult result =
                mvc.perform(
                                MockMvcRequestBuilders.post(ProbeController.PATH)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(validBody()))
                        .andReturn();

        assertEquals(200, result.getResponse().getStatus());
        JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
        assertTrue(body.get("accepted").asBoolean());
        assertEquals(42, body.get("id").asLong());
        assertNotNull(result.getResponse().getHeader(RequestIdFilter.HEADER));
    }

    private JsonNode assertError(String json, int status, String code) throws Exception {
        JsonNode body = perform(json.getBytes(StandardCharsets.UTF_8));

        assertEquals(status, body.get("status").asInt());
        assertEquals(code, body.get("errorCode").asString());
        assertFalse(body.get("retryable").asBoolean());
        assertTrue(body.get("requestId").asString().length() > 0);
        String raw = body.toString();
        for (String detail : new String[] {"message", "stack", "exception", "extra", "orders"}) {
            assertFalse(raw.contains(detail), () -> "Error body must not contain " + detail);
        }
        return body;
    }

    private JsonNode perform(byte[] body) throws Exception {
        MvcResult result =
                mvc.perform(
                                MockMvcRequestBuilders.post(ProbeController.PATH)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andReturn();
        return JSON.readTree(result.getResponse().getContentAsByteArray());
    }

    private static byte[] validBody() {
        return "{\"name\":\"orders\",\"id\":\"42\"}".getBytes(StandardCharsets.UTF_8);
    }

    private static Set<String> fieldNames(JsonNode body) {
        Set<String> names = new java.util.LinkedHashSet<>();
        body.propertyNames().forEach(names::add);
        return names;
    }

    private record ProbePayload(String name, long id) {}

    @RestController
    static final class ProbeController {
        static final String PATH = "/__ptmcp_boundary_probe__";

        @PostMapping(PATH)
        Map<String, Object> probe(@RequestBody(required = false) byte[] body) {
            ProbePayload payload = StrictJson.parse(body, ProbePayload.class);
            return Map.of("accepted", true, "id", payload.id());
        }
    }

    @RestControllerAdvice
    static final class ProbeErrors {
        @ExceptionHandler(DomainException.class)
        ResponseEntity<Map<String, Object>> mapError(
                DomainException error, HttpServletRequest request) {
            ErrorDescriptor descriptor = ErrorMapping.descriptor(error.errorCode());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", descriptor.status().value());
            body.put("errorCode", descriptor.errorCode().name());
            body.put("retryable", descriptor.retryable());
            String requestId = RequestIdFilter.requestId(request);
            if (requestId != null) {
                body.put("requestId", requestId);
            }
            return ResponseEntity.status(descriptor.status()).body(body);
        }
    }
}
