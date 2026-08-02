package com.idea2strategy.backend.application.competition;

import java.math.BigDecimal;
import java.util.Objects;

public record OwnedBotComparisonItem(
        Integer rank,
        boolean jointRank,
        String anonymousAlias,
        BigDecimal score,
        String eligibilityStatus,
        BigDecimal equityAmount,
        BigDecimal totalReturnPct,
        BigDecimal maxDrawdownPct,
        BigDecimal sharpeRatio,
        OwnedLeaderboardEvidence evidence) {
    public OwnedBotComparisonItem {
        Objects.requireNonNull(evidence, "evidence");
    }
}
