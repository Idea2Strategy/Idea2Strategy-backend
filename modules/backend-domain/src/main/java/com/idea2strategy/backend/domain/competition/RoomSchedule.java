package com.idea2strategy.backend.domain.competition;

import java.time.Instant;
import java.util.Objects;

public record RoomSchedule(
        Instant recruitmentOpensAt,
        Instant participationOpensAt,
        Instant evaluationStartsAt,
        Instant participationClosesAt,
        Instant evaluationEndsAt,
        Instant finalizationDeadlineAt,
        String timezoneName) {

    public RoomSchedule {
        Objects.requireNonNull(recruitmentOpensAt, "recruitmentOpensAt");
        Objects.requireNonNull(participationOpensAt, "participationOpensAt");
        Objects.requireNonNull(evaluationStartsAt, "evaluationStartsAt");
        Objects.requireNonNull(participationClosesAt, "participationClosesAt");
        Objects.requireNonNull(evaluationEndsAt, "evaluationEndsAt");
        Objects.requireNonNull(finalizationDeadlineAt, "finalizationDeadlineAt");
        Objects.requireNonNull(timezoneName, "timezoneName");
        if (timezoneName.isBlank() || timezoneName.length() > 80) {
            throw new IllegalArgumentException("timezoneName must contain 1..80 characters");
        }
        if (recruitmentOpensAt.isAfter(participationOpensAt)) {
            throw new IllegalArgumentException("recruitment must open before participation");
        }
        if (participationOpensAt.isAfter(participationClosesAt)) {
            throw new IllegalArgumentException("participation window is invalid");
        }
        if (evaluationStartsAt.isAfter(evaluationEndsAt)) {
            throw new IllegalArgumentException("evaluation window is invalid");
        }
        if (participationClosesAt.isAfter(evaluationEndsAt)) {
            throw new IllegalArgumentException("participation must close before evaluation ends");
        }
        if (evaluationEndsAt.isAfter(finalizationDeadlineAt)) {
            throw new IllegalArgumentException("finalization must follow evaluation");
        }
    }
}
