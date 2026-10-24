package local.ptmcp.admin.api;

import jakarta.servlet.http.HttpServletRequest;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

/** If-Match carries the expected aggregate version; absence is a precondition failure. */
public final class IfMatch {
    private IfMatch() {}

    public static long requireVersion(HttpServletRequest request) {
        String header = request.getHeader("If-Match");
        if (header == null || header.isBlank()) {
            throw new DomainException(ErrorCode.PRECONDITION_REQUIRED);
        }
        String value = header.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        try {
            long version = Long.parseLong(value);
            if (version < 1) {
                throw new DomainException(ErrorCode.INVALID_ARGUMENT);
            }
            return version;
        } catch (NumberFormatException failure) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
    }

    public static String etag(long version) {
        return "\"" + version + "\"";
    }
}
