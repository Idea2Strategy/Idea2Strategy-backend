package com.idea2strategy.backend.api.strategy;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = StrategyDraftController.class)
public class StrategyAuthoringExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalidDraft(IllegalArgumentException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setTitle("Invalid strategy draft");
        return problem;
    }
}
