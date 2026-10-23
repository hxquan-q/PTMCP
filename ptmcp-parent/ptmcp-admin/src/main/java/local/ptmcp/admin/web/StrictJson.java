package local.ptmcp.admin.web;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Parses request JSON with strict boundaries: UTF-8 only, no duplicate keys, no unknown fields, and
 * a 1 MiB body cap.
 *
 * <p>Numeric identifiers are accepted as JSON strings so 64-bit ids are never routed through
 * JavaScript number precision. Failures surface only as registered error codes.
 */
public final class StrictJson {
    public static final int MAX_BODY_BYTES = 1024 * 1024;

    private static final JsonMapper MAPPER =
            JsonMapper.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .build();

    private StrictJson() {}

    public static <T> T parse(byte[] body, Class<T> type) {
        if (body == null || type == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (body.length > MAX_BODY_BYTES) {
            throw new DomainException(ErrorCode.METADATA_TOO_LARGE);
        }
        T value;
        try {
            value = MAPPER.readValue(decodeUtf8(body), type);
        } catch (JacksonException error) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        if (value == null) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
        return value;
    }

    private static String decodeUtf8(byte[] body) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(body))
                    .toString();
        } catch (CharacterCodingException error) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
    }
}
