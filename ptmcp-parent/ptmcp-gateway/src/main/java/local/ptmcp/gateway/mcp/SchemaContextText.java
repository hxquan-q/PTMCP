package local.ptmcp.gateway.mcp;

/** One schema response's untrusted text budget, independent of the final serialized body limit. */
final class SchemaContextText {
    static final int FIELD_BYTES = 2048;
    static final int TOTAL_BYTES = 32768;

    private int remaining = TOTAL_BYTES;
    private boolean truncated;

    record Text(String value, boolean truncated) {}

    Text take(String value) {
        return take(value, false);
    }

    Text take(String value, boolean alreadyTruncated) {
        if (value == null) {
            return new Text(null, false);
        }
        int limit = Math.min(FIELD_BYTES, remaining);
        int end = 0;
        int bytes = 0;
        while (end < value.length()) {
            int codePoint = value.codePointAt(end);
            int width =
                    codePoint <= 0x7f ? 1 : codePoint <= 0x7ff ? 2 : codePoint <= 0xffff ? 3 : 4;
            if (bytes + width > limit) {
                break;
            }
            bytes += width;
            end += Character.charCount(codePoint);
        }
        remaining -= bytes;
        boolean shortened = alreadyTruncated || end < value.length();
        truncated |= shortened;
        return new Text(value.substring(0, end), shortened);
    }

    boolean truncated() {
        return truncated;
    }
}
