package com.idea2strategy.backend.api.caseoperations;

import com.idea2strategy.backend.application.caseoperations.OperatorCaseAuthenticationRejectedException;
import com.idea2strategy.backend.application.caseoperations.OperatorCaseIdempotencyConflictException;
import com.idea2strategy.backend.application.caseoperations.OperatorCaseQueryRejectedException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = OperatorCaseController.class)
class OperatorCaseExceptionHandler {
    @ExceptionHandler(OperatorCaseAuthenticationRejectedException.class)
    ResponseEntity<ErrorResponse> authentication(OperatorCaseAuthenticationRejectedException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse(exception.getMessage(), null));
    }

    @ExceptionHandler(OperatorCaseIdempotencyConflictException.class)
    ResponseEntity<ErrorResponse> idempotency(OperatorCaseIdempotencyConflictException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse(exception.getMessage(), null));
    }

    @ExceptionHandler(OperatorCaseQueryRejectedException.class)
    ResponseEntity<ErrorResponse> query(OperatorCaseQueryRejectedException exception) {
        HttpStatus status = "CASE_NOT_AVAILABLE".equals(exception.getMessage())
                ? HttpStatus.NOT_FOUND : HttpStatus.FORBIDDEN;
        return ResponseEntity.status(status).body(new ErrorResponse(exception.getMessage(), null));
    }

    @ExceptionHandler(OperatorCaseRejectedException.class)
    ResponseEntity<ErrorResponse> command(OperatorCaseRejectedException exception) {
        return ResponseEntity.status(exception.status())
                .body(new ErrorResponse(exception.getMessage(), exception.correlationId()));
    }

    record ErrorResponse(String code, UUID correlationId) {}
}
