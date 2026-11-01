package local.ptmcp.authz.oauth;

import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class OAuthStoreSupport {
    static final JsonMapper JSON = JsonMapper.builder().build();

    private OAuthStoreSupport() {}

    static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is required", failure);
        }
    }

    static List<String> splitCsv(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .forEach(values::add);
        return values;
    }

    /**
     * Canonical rows persist JSON arrays so commas and spaces survive inside values. Legacy rows
     * are split by their historical writer format only — newline-joined URIs, space-joined scopes,
     * comma CSV otherwise — a comma inside a legacy URI must never be a separator. Malformed
     * canonical values fail closed as empty, which the caller turns into an unusable client.
     */
    static List<String> splitCompat(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String trimmed = value.trim();
        if (trimmed.charAt(0) == '[') {
            try {
                List<String> parsed = new ArrayList<>();
                for (Object entry : (List<?>) JSON.readValue(trimmed, List.class)) {
                    parsed.add(String.valueOf(entry));
                }
                return List.copyOf(parsed);
            } catch (RuntimeException failure) {
                return List.of();
            }
        }
        String legacySeparator =
                trimmed.indexOf('\n') >= 0 ? "\n" : trimmed.indexOf(' ') >= 0 ? " " : ",";
        List<String> values = new ArrayList<>();
        Arrays.stream(trimmed.split(legacySeparator))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .forEach(values::add);
        return List.copyOf(values);
    }

    static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    static LocalDateTime toUtc(Instant value) {
        return value == null
                ? null
                : LocalDateTime.ofInstant(value, ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }

    static String toJson(Object value) {
        return JSON.writeValueAsString(value);
    }
}
