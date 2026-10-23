package local.ptmcp.common.resource;

/**
 * A positive metadata BIGINT identity. Web/MCP adapters must use its decimal string, never a JSON
 * number or floating-point conversion. This value alone conveys no authorization.
 */
public record ResourceId(long value) {
    private static final String INVALID_ID =
            "Resource ID must be a positive signed BIGINT in ASCII decimal form";

    public ResourceId {
        if (value <= 0) {
            throw new IllegalArgumentException(INVALID_ID);
        }
    }

    public static ResourceId parse(String value) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(INVALID_ID);
        }
        for (int index = 0; index < value.length(); index++) {
            char digit = value.charAt(index);
            if (digit < '0' || digit > '9') {
                throw new IllegalArgumentException(INVALID_ID);
            }
        }
        try {
            return new ResourceId(Long.parseLong(value));
        } catch (NumberFormatException exception) {
            // Do not propagate parseLong's exception message, which echoes untrusted input.
            throw new IllegalArgumentException(INVALID_ID);
        }
    }

    @Override
    public String toString() {
        return Long.toString(value);
    }
}
