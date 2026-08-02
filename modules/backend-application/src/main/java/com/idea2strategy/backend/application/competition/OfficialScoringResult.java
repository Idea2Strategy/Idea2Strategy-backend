package com.idea2strategy.backend.application.competition;

import com.idea2strategy.backend.domain.competition.ScoringDirection;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.UUID;

public record OfficialScoringResult(
        UUID participationId,
        BigDecimal score,
        ScoringDirection scoreDirection,
        OfficialScoringMetrics metrics) {
    public OfficialScoringResult {
        Objects.requireNonNull(participationId, "participationId");
        score = Objects.requireNonNull(score, "score").setScale(8, RoundingMode.HALF_EVEN);
        Objects.requireNonNull(scoreDirection, "scoreDirection");
        Objects.requireNonNull(metrics, "metrics");
    }
}
