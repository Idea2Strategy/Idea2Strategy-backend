package com.idea2strategy.backend.api.operatorrbac;

import com.idea2strategy.backend.application.operatorrbac.OperatorRbacAuthenticationRejectedException;
import com.idea2strategy.backend.application.operatorrbac.OperatorRbacIdempotencyConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = OperatorRbacController.class)
public class OperatorRbacExceptionHandler {
    @ExceptionHandler(OperatorRbacAuthenticationRejectedException.class)
    ProblemDetail unauthenticated(OperatorRbacAuthenticationRejectedException exception) {
        return problem(HttpStatus.UNAUTHORIZED, exception.getMessage(), null);
    }

    @ExceptionHandler(OperatorRbacIdempotencyConflictException.class)
    ProblemDetail idempotencyConflict(OperatorRbacIdempotencyConflictException exception) {
        return problem(HttpStatus.CONFLICT, exception.getMessage(), null);
    }

    @ExceptionHandler(OperatorRbacRejectedException.class)
    ProblemDetail rejected(OperatorRbacRejectedException exception) {
        return problem(HttpStatus.valueOf(exception.responseStatus()), exception.getMessage(),
                exception.correlationId().toString());
    }

    private static ProblemDetail problem(HttpStatus status, String code, String correlationId) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(status, code);
        detail.setProperty("code", code);
        if (correlationId != null) detail.setProperty("correlationId", correlationId);
        return detail;
    }
}
