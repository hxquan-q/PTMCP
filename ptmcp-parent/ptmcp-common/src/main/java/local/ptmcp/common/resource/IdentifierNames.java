package local.ptmcp.common.resource;

/** Shared storage/encoding bounds only; deliberately not database identifier syntax validation. */
final class IdentifierNames {
    private static final int MAX_CODEPOINTS = 128;

    private IdentifierNames() {}

    static void requireValid(String value, String fieldName) {
        if (value == null || value.isEmpty()) {
            throw invalidName(fieldName);
        }
        int codepoints = 0;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '\0' || Character.isLowSurrogate(character)) {
                throw invalidName(fieldName);
            }
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw invalidName(fieldName);
                }
                index++;
            }
            if (++codepoints > MAX_CODEPOINTS) {
                throw invalidName(fieldName);
            }
        }
    }

    private static IllegalArgumentException invalidName(String fieldName) {
        // Field names are internal constants; never echo snapshot names in an exception.
        return new IllegalArgumentException(
                fieldName + " must be 1 to 128 Unicode codepoints without NUL or malformed UTF-16");
    }
}
