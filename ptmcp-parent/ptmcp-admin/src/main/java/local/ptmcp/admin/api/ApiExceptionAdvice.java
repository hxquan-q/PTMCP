package local.ptmcp.admin.api;

import local.ptmcp.admin.web.ErrorDescriptor;
import local.ptmcp.admin.web.ErrorMapping;
import local.ptmcp.common.error.DomainException;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Projects domain failures onto the api-spec §10 status table without leaking internals. */
@RestControllerAdvice
@ConditionalOnProperty("ptmcp.admin.datasource.url")
public class ApiExceptionAdvice {
    public record ErrorBody(String code, int codeNumber, boolean retryable) {}

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ErrorBody> handle(DomainException error) {
        ErrorDescriptor descriptor = ErrorMapping.descriptor(error.errorCode());
        return ResponseEntity.status(descriptor.status())
                .body(
                        new ErrorBody(
                                error.errorCode().name(),
                                error.errorCode().restCode(),
                                descriptor.retryable()));
    }
}
