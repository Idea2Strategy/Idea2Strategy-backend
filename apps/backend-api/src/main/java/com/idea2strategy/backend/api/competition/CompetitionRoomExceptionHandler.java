package com.idea2strategy.backend.api.competition;

import com.idea2strategy.backend.application.competition.ScoringTemplateNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = CompetitionRoomController.class)
public class CompetitionRoomExceptionHandler {
    @ExceptionHandler(ScoringTemplateNotFoundException.class)
    ProblemDetail templateNotFound(ScoringTemplateNotFoundException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
        problem.setTitle("Scoring template not selectable");
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalidRoom(IllegalArgumentException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setTitle("Invalid competition room");
        return problem;
    }
}
