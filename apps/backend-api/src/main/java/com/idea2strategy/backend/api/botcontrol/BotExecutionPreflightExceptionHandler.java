package com.idea2strategy.backend.api.botcontrol;

import com.idea2strategy.backend.application.botcontrol.BotExecutionPreflightNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = BotExecutionPreflightController.class)
public class BotExecutionPreflightExceptionHandler {
    @ExceptionHandler(BotExecutionPreflightNotFoundException.class)
    ProblemDetail notFound(BotExecutionPreflightNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
        problem.setTitle(HttpStatus.NOT_FOUND.getReasonPhrase());
        return problem;
    }
}
