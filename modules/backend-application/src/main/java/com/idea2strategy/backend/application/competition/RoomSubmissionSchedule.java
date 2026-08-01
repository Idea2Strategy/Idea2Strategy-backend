package com.idea2strategy.backend.application.competition;

import com.idea2strategy.backend.domain.competition.CompetitionType;
import com.idea2strategy.backend.domain.competition.RoomStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record RoomSubmissionSchedule(
        CompetitionType competitionType,
        RoomStatus roomStatus,
        Instant participationOpensAt,
        Instant evaluationStartsAt,
        Instant participationClosesAt) {
    public RoomSubmissionSchedule {
        Objects.requireNonNull(competitionType, "competitionType");
        Objects.requireNonNull(roomStatus, "roomStatus");
        Objects.requireNonNull(participationOpensAt, "participationOpensAt");
        Objects.requireNonNull(evaluationStartsAt, "evaluationStartsAt");
        Objects.requireNonNull(participationClosesAt, "participationClosesAt");
    }

    public Optional<RoomSubmissionTiming> timingAt(Instant submittedAt) {
        Objects.requireNonNull(submittedAt, "submittedAt");
        if (submittedAt.isBefore(participationOpensAt) || !submittedAt.isBefore(participationClosesAt)) {
            return Optional.empty();
        }
        if (competitionType == CompetitionType.LIVE_PAPER) {
            return roomStatus == RoomStatus.RECRUITING && submittedAt.isBefore(evaluationStartsAt)
                    ? Optional.of(RoomSubmissionTiming.WAIT_UNTIL_EVALUATION)
                    : Optional.empty();
        }
        if (roomStatus != RoomStatus.RECRUITING && roomStatus != RoomStatus.EVALUATING) {
            return Optional.empty();
        }
        return Optional.of(submittedAt.isBefore(evaluationStartsAt)
                ? RoomSubmissionTiming.WAIT_UNTIL_EVALUATION
                : RoomSubmissionTiming.START_IMMEDIATELY);
    }
}
