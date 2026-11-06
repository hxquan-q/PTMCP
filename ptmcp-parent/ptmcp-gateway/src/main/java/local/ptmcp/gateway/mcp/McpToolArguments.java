package local.ptmcp.gateway.mcp;

import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Strict tools/call argument parsing and opaque catalog cursors; malformed input throws {@link
 * IllegalArgumentException} naming the field.
 */
final class McpToolArguments {
    private McpToolArguments() {}

    // ---------------------------------------------------------------- argument parsing

    static String requiredText(JsonNode args, String field) {
        JsonNode value = args.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(field);
        }
        return value.asText();
    }

    static String optionalText(JsonNode args, String field) {
        JsonNode value = args.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException(field);
        }
        return value.asText();
    }

    static int optionalInt(JsonNode args, String field, int fallback) {
        JsonNode value = args.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return fallback;
        }
        if (!value.isNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException(field);
        }
        return value.asInt();
    }

    static List<String> textArray(JsonNode args, String field, int maxItems) {
        JsonNode value = args.path(field);
        if (!value.isArray() || value.isEmpty() || value.size() > maxItems) {
            throw new IllegalArgumentException(field);
        }
        Set<String> unique = new TreeSet<>();
        for (JsonNode entry : value) {
            if (!entry.isTextual() || entry.asText().isBlank()) {
                throw new IllegalArgumentException(field);
            }
            unique.add(entry.asText());
        }
        if (unique.size() != value.size()) {
            throw new IllegalArgumentException(field);
        }
        return List.copyOf(unique);
    }

    static UUID parseRequestId(String candidate) {
        if (candidate != null) {
            try {
                return UUID.fromString(candidate);
            } catch (IllegalArgumentException ignored) {
                // Fall through to a fresh id; the boundary always owns the request identity.
            }
        }
        return UUID.randomUUID();
    }

    // ---------------------------------------------------------------- cursor helpers

    record CursorPosition(long namespaceId, long objectId) {
        boolean before(long namespaceId, long objectId) {
            return namespaceId > this.namespaceId
                    || (namespaceId == this.namespaceId && objectId > this.objectId);
        }
    }

    static CursorPosition decodeCursor(String cursor) {
        if (cursor == null) {
            return null;
        }
        try {
            String decoded =
                    new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
            int split = decoded.indexOf(':');
            if (split <= 0 || split == decoded.length() - 1) {
                throw new IllegalArgumentException("cursor");
            }
            return new CursorPosition(
                    Long.parseLong(decoded.substring(0, split)),
                    Long.parseLong(decoded.substring(split + 1)));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("cursor");
        }
    }

    static String encodeCursor(long namespaceId, long objectId) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString((namespaceId + ":" + objectId).getBytes(StandardCharsets.US_ASCII));
    }
}
