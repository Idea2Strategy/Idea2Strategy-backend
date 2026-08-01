package com.idea2strategy.backend.api.competition;

import com.idea2strategy.backend.application.competition.ScoringTemplateNotFoundException;
import com.idea2strategy.backend.application.competition.OperatorAuthorizationException;
import com.idea2strategy.backend.application.competition.RoomInvitationAccessException;
import com.idea2strategy.backend.application.competition.RoomInvitationUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {
    CompetitionRoomController.class,
    OfficialCompetitionRoomController.class,
    PublicRoomDiscoveryController.class,
    RoomInvitationController.class
})
public class CompetitionRoomExceptionHandler {
    @ExceptionHandler(RoomInvitationAccessException.class)
    ProblemDetail invitationAccessDenied(RoomInvitationAccessException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, exception.getMessage());
        problem.setTitle("Room invitation access denied");
        return problem;
    }

    @ExceptionHandler(RoomInvitationUnavailableException.class)
    ProblemDetail invitationUnavailable(RoomInvitationUnavailableException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.GONE, exception.getMessage());
        problem.setTitle("Room invitation unavailable");
        return problem;
    }

    @ExceptionHandler(OperatorAuthorizationException.class)
    ProblemDetail operatorRequired(OperatorAuthorizationException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, exception.getMessage());
        problem.setTitle("Platform operator authorization required");
        return problem;
    }

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
