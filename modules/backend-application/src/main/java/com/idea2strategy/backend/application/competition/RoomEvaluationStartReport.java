package com.idea2strategy.backend.application.competition;

import java.time.Instant;
import java.util.Objects;

public record RoomEvaluationStartReport(Instant observedAt, int participantsStarted) {
    public RoomEvaluationStartReport {
        Objects.requireNonNull(observedAt, "observedAt");
        if (participantsStarted < 0) {
            throw new IllegalArgumentException("participantsStarted must not be negative");
        }
    }
}
