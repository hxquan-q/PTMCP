package local.ptmcp.common.error;

import java.util.Objects;

/**
 * A domain failure carrying only a registered code, not raw SQL or infrastructure exception text.
 * Adapters map the code to their own protocol; they must not serialize this exception.
 */
public final class DomainException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    public DomainException(ErrorCode errorCode) {
        super(Objects.requireNonNull(errorCode, "errorCode").name());
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
