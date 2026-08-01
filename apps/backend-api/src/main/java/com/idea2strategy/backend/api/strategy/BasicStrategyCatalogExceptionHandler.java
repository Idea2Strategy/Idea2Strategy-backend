package com.idea2strategy.backend.api.strategy;

import com.idea2strategy.backend.application.strategy.StrategyCatalogNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = BasicStrategyCatalogController.class)
public class BasicStrategyCatalogExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalidRequest(IllegalArgumentException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setTitle("Invalid Basic strategy catalog query");
        return problem;
    }

    @ExceptionHandler(StrategyCatalogNotFoundException.class)
    ProblemDetail catalogNotFound(StrategyCatalogNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
        problem.setTitle("Basic strategy catalog not found");
        return problem;
    }
}
