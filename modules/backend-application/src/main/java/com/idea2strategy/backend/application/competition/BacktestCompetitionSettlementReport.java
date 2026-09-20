package com.idea2strategy.backend.application.competition;

import java.time.Instant;
import java.util.Objects;

public record BacktestCompetitionSettlementReport(
        Instant observedAt,
        int participantsCompleted,
        int participantsFailed,
        int publishedSnapshots,
        int finalSnapshots) {
    public BacktestCompetitionSettlementReport {
        Objects.requireNonNull(observedAt, "observedAt");
        if (participantsCompleted < 0 || participantsFailed < 0
                || publishedSnapshots < 0 || finalSnapshots < 0) {
            throw new IllegalArgumentException("settlement counts must be non-negative");
        }
    }
}
