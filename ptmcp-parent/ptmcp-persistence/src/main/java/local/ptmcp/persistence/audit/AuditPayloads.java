package local.ptmcp.persistence.audit;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Canonical, versioned outbox payloads. Only whitelisted tool names, registered error-code shapes
 * and caller-masked SQL reach this writer; raw SQL, credentials and results have no parameter.
 */
final class AuditPayloads {
    private static final int SCHEMA_VERSION = 1;

    private AuditPayloads() {}

    static Payload start(AuditStart start) {
        StringBuilder json = new StringBuilder(320);
        json.append("{\"schemaVersion\":").append(SCHEMA_VERSION);
        json.append(",\"eventKind\":\"START\"");
        json.append(",\"requestId\":");
        appendString(json, start.requestId().toString());
        json.append(",\"userId\":").append(start.userId());
        json.append(",\"platformId\":").append(start.platformId());
        json.append(",\"tool\":");
        appendString(json, start.tool());
        json.append(",\"sqlHash\":");
        appendHexOrNull(json, start.sqlHash());
        json.append(",\"rawLength\":").append(start.rawLength());
        json.append(",\"startedAt\":");
        appendString(json, start.startedAt().toString());
        json.append('}');
        return payload(json);
    }

    static Payload terminal(AuditTerminal terminal) {
        StringBuilder json = new StringBuilder(640);
        json.append("{\"schemaVersion\":").append(SCHEMA_VERSION);
        json.append(",\"eventKind\":\"TERMINAL\"");
        json.append(",\"requestId\":");
        appendString(json, terminal.requestId().toString());
        json.append(",\"userId\":").append(terminal.userId());
        json.append(",\"platformId\":").append(terminal.platformId());
        json.append(",\"datasourceId\":").append(terminal.datasourceId());
        json.append(",\"tool\":");
        appendString(json, terminal.tool());
        json.append(",\"startedAt\":");
        appendString(json, terminal.startedAt().toString());
        json.append(",\"result\":");
        appendString(json, terminal.result().name());
        json.append(",\"verdict\":");
        appendString(json, terminal.verdict().name());
        json.append(",\"errorCode\":");
        appendString(json, terminal.errorCode());
        json.append(",\"durationMs\":").append(terminal.durationMs());
        json.append(",\"queueMs\":").append(terminal.queueMs());
        json.append(",\"rowsReturned\":").append(terminal.rowsReturned());
        json.append(",\"truncated\":").append(terminal.truncated());
        json.append(",\"maskedSql\":");
        appendString(json, terminal.maskedSql());
        json.append(",\"sqlFingerprint\":");
        appendHexOrNull(json, terminal.sqlFingerprint());
        json.append(",\"encryptedRaw\":");
        appendBase64OrNull(json, terminal.encryptedRaw());
        json.append(",\"keyId\":");
        appendString(json, terminal.keyId());
        json.append('}');
        return payload(json);
    }

    private static Payload payload(StringBuilder json) {
        byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
        return new Payload(bytes, sha256(bytes));
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException error) {
            throw new DomainException(ErrorCode.INTERNAL_ERROR);
        }
    }

    private static void appendHexOrNull(StringBuilder json, byte[] value) {
        if (value == null) {
            json.append("null");
            return;
        }
        appendString(json, HexFormat.of().formatHex(value));
    }

    private static void appendBase64OrNull(StringBuilder json, byte[] value) {
        appendString(json, value == null ? null : Base64.getEncoder().encodeToString(value));
    }

    private static void appendString(StringBuilder json, String value) {
        if (value == null) {
            json.append("null");
            return;
        }
        json.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (character < 0x20) {
                        json.append("\\u00");
                        json.append(Character.forDigit(character >> 4, 16));
                        json.append(Character.forDigit(character & 0xF, 16));
                    } else if (Character.isHighSurrogate(character)
                            && index + 1 < value.length()
                            && Character.isLowSurrogate(value.charAt(index + 1))) {
                        json.append(character).append(value.charAt(++index));
                    } else if (Character.isSurrogate(character)) {
                        // An unpaired surrogate cannot be encoded as UTF-8; keep the payload valid.
                        json.append('\uFFFD');
                    } else {
                        json.append(character);
                    }
                }
            }
        }
        json.append('"');
    }

    record Payload(byte[] bytes, byte[] hash) {
        Payload {
            bytes = bytes.clone();
            hash = hash.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }

        @Override
        public byte[] hash() {
            return hash.clone();
        }
    }

    static boolean sameHash(byte[] expected, byte[] actual) {
        return expected != null && actual != null && MessageDigest.isEqual(expected, actual);
    }
}
