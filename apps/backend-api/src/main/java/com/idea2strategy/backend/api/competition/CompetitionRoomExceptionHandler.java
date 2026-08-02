package com.idea2strategy.backend.api.competition;

import com.idea2strategy.backend.application.competition.InvalidLeaderboardCursorException;
import com.idea2strategy.backend.application.competition.LeaderboardAccessException;
import com.idea2strategy.backend.application.competition.LeaderboardAuthenticationException;
import com.idea2strategy.backend.application.competition.OperatorAuthorizationException;
import com.idea2strategy.backend.application.competition.PostEvaluationChoiceAccessException;
import com.idea2strategy.backend.application.competition.PostEvaluationChoiceConflictException;
import com.idea2strategy.backend.application.competition.RoomConfigurationAccessException;
import com.idea2strategy.backend.application.competition.RoomConfigurationConflictException;
import com.idea2strategy.backend.application.competition.RoomInvitationAccessException;
import com.idea2strategy.backend.application.competition.RoomInvitationUnavailableException;
import com.idea2strategy.backend.application.competition.RoomParticipationAdmissionException;
import com.idea2strategy.backend.application.competition.RoomTerminationAccessException;
import com.idea2strategy.backend.application.competition.RoomTerminationConflictException;
import com.idea2strategy.backend.application.competition.ScoringTemplateNotFoundException;
import com.idea2strategy.backend.application.strategy.ImmutableStrategyReleaseRejectedException;
import com.idea2strategy.backend.application.strategy.StrategyCatalogNotFoundException;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {
    CompetitionRoomController.class,
    OfficialCompetitionRoomController.class,
    PublicRoomDiscoveryController.class,
    RoomInvitationController.class,
    RoomParticipationController.class,
    PlatformRoomInvalidationController.class,
    RoomTerminationController.class,
    RoomConfigurationController.class,
    PostEvaluationChoiceController.class,
    AnonymousLeaderboardController.class,
    OwnedBotComparisonController.class
})
public class CompetitionRoomExceptionHandler {
    @ExceptionHandler(InvalidLeaderboardCursorException.class)
    ProblemDetail invalidLeaderboardCursor(InvalidLeaderboardCursorException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setTitle("Invalid leaderboard cursor");
        return problem;
    }

    @ExceptionHandler(LeaderboardAuthenticationException.class)
    ProblemDetail leaderboardAuthenticationRequired(LeaderboardAuthenticationException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, exception.getMessage());
        problem.setTitle("Leaderboard authentication required");
        return problem;
    }

    @ExceptionHandler(LeaderboardAccessException.class)
    ProblemDetail leaderboardAccessDenied(LeaderboardAccessException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, exception.getMessage());
        problem.setTitle("Leaderboard access denied");
        return problem;
    }

    @ExceptionHandler(PostEvaluationChoiceAccessException.class)
    ProblemDetail postEvaluationChoiceAccessDenied(PostEvaluationChoiceAccessException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, exception.getMessage());
        problem.setTitle("Post-evaluation choice access denied");
        return problem;
    }

    @ExceptionHandler(PostEvaluationChoiceConflictException.class)
    ProblemDetail postEvaluationChoiceConflict(PostEvaluationChoiceConflictException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
        problem.setTitle("Post-evaluation choice rejected");
        return problem;
    }

    @ExceptionHandler(RoomConfigurationAccessException.class)
    ProblemDetail configurationAccessDenied(RoomConfigurationAccessException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, exception.getMessage());
        problem.setTitle("Room configuration access denied");
        return problem;
    }

    @ExceptionHandler(RoomConfigurationConflictException.class)
    ProblemDetail configurationConflict(RoomConfigurationConflictException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
        problem.setTitle("Room configuration rejected");
        return problem;
    }

    @ExceptionHandler(RoomTerminationAccessException.class)
    ProblemDetail terminationAccessDenied(RoomTerminationAccessException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, exception.getMessage());
        problem.setTitle("Room termination access denied");
        return problem;
    }

    @ExceptionHandler(RoomTerminationConflictException.class)
    ProblemDetail terminationConflict(RoomTerminationConflictException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
        problem.setTitle("Room termination rejected");
        return problem;
    }

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

    @ExceptionHandler({StrategyCatalogNotFoundException.class, NoSuchElementException.class})
    ProblemDetail strategyNotFound(RuntimeException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, exception.getMessage());
        problem.setTitle("Strategy selection not found");
        return problem;
    }

    @ExceptionHandler(RoomParticipationAdmissionException.class)
    ProblemDetail participationRejected(RoomParticipationAdmissionException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
        problem.setTitle("Room participation rejected");
        problem.setProperty("code", exception.failure().name());
        return problem;
    }

    @ExceptionHandler({IllegalStateException.class, ImmutableStrategyReleaseRejectedException.class})
    ProblemDetail strategyRejected(RuntimeException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
        problem.setTitle("Strategy is not eligible for room participation");
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalidRoom(IllegalArgumentException exception) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        problem.setTitle("Invalid competition room");
        return problem;
    }
}
