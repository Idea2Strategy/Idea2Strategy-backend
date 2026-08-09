package com.idea2strategy.backend.api.botoperations;

import com.idea2strategy.backend.application.botoperations.BotOperationsNotFoundException;
import com.idea2strategy.backend.application.botoperations.BotDeletionConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {BotOperationsController.class, BotDeletionController.class})
public class BotOperationsExceptionHandler {
    @ExceptionHandler(BotOperationsNotFoundException.class)
    ProblemDetail notFound(BotOperationsNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, exception.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException exception) {
        return problem(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(BotDeletionConflictException.class)
    ProblemDetail conflict(BotDeletionConflictException exception) {
        return problem(HttpStatus.CONFLICT, exception.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        return problem;
    }
}
