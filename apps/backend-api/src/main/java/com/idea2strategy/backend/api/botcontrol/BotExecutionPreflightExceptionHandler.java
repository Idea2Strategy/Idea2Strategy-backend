package com.idea2strategy.backend.api.botcontrol;

import com.idea2strategy.backend.application.botcontrol.BotExecutionPreflightNotFoundException;
import com.idea2strategy.backend.application.botcontrol.BotRunCommandConflictException;
import com.idea2strategy.backend.application.botcontrol.BotRunCommandRejectedException;
import com.idea2strategy.backend.application.botcontrol.BotStopCommandConflictException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {
    BotExecutionPreflightController.class,
    BotRunCommandController.class,
    BotStopCommandController.class
})
public class BotExecutionPreflightExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setTitle(HttpStatus.BAD_REQUEST.getReasonPhrase());
        return problem;
    }

    @ExceptionHandler(BotExecutionPreflightNotFoundException.class)
    ProblemDetail notFound(BotExecutionPreflightNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
        problem.setTitle(HttpStatus.NOT_FOUND.getReasonPhrase());
        return problem;
    }

    @ExceptionHandler(BotRunCommandRejectedException.class)
    ProblemDetail preflightRejected(BotRunCommandRejectedException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
        problem.setTitle(HttpStatus.CONFLICT.getReasonPhrase());
        problem.setProperty("issues", exception.issues());
        return problem;
    }

    @ExceptionHandler({BotRunCommandConflictException.class, BotStopCommandConflictException.class})
    ProblemDetail commandConflict(IllegalStateException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
        problem.setTitle(HttpStatus.CONFLICT.getReasonPhrase());
        return problem;
    }
}
