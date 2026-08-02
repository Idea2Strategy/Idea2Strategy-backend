package com.idea2strategy.backend.application.competition;

import java.util.Objects;

public record OfficialScoringRank(int rank, OfficialScoringResult result) {
    public OfficialScoringRank {
        if (rank < 1) {
            throw new IllegalArgumentException("rank must be positive");
        }
        Objects.requireNonNull(result, "result");
    }
}
