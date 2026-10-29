package local.ptmcp.persistence.audit;

import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

/** Durable process identity; clients cannot choose it through tool arguments or headers. */
public record ExecutionOwner(String instanceId, String bootId) {
    public ExecutionOwner {
        if (instanceId == null
                || !instanceId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
                || bootId == null
                || !bootId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,35}")) {
            throw new DomainException(ErrorCode.INVALID_ARGUMENT);
        }
    }
}
