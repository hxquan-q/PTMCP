package local.ptmcp.admin.web;

import local.ptmcp.common.error.ErrorCode;

import org.springframework.http.HttpStatus;

/** HTTP projection of one registered domain error code. */
public record ErrorDescriptor(ErrorCode errorCode, HttpStatus status, boolean retryable) {
    public ErrorDescriptor {
        if (errorCode == null || status == null) {
            throw new IllegalArgumentException("errorCode and status are required");
        }
    }
}
