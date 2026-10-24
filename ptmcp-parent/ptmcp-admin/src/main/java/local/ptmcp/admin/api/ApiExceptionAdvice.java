package local.ptmcp.admin.api;

import local.ptmcp.admin.web.ErrorDescriptor;
import local.ptmcp.admin.web.ErrorMapping;
import local.ptmcp.common.error.DomainException;
import local.ptmcp.common.error.ErrorCode;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Projects domain failures onto the api-spec §10 status table without leaking internals. */
@RestControllerAdvice
@ConditionalOnProperty("ptmcp.admin.datasource.url")
public class ApiExceptionAdvice {
    public record ErrorBody(String code, int codeNumber, boolean retryable) {}

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ErrorBody> handle(DomainException error) {
        return response(error.errorCode());
    }

    /** Spring's own binding failures must use the same stable body as domain rejections. */
    @ExceptionHandler({
        MissingServletRequestParameterException.class,
        MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ErrorBody> handleBindingFailure() {
        return response(ErrorCode.INVALID_ARGUMENT);
    }

    private static ResponseEntity<ErrorBody> response(ErrorCode code) {
        ErrorDescriptor descriptor = ErrorMapping.descriptor(code);
        return ResponseEntity.status(descriptor.status())
                .body(new ErrorBody(code.name(), code.restCode(), descriptor.retryable()));
    }
}
