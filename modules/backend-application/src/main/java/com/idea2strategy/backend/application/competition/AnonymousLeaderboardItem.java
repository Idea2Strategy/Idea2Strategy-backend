package com.idea2strategy.backend.application.competition;

import java.math.BigDecimal;

public record AnonymousLeaderboardItem(
        int rank,
        boolean jointRank,
        String anonymousAlias,
        BigDecimal score,
        String eligibilityStatus,
        BigDecimal equityAmount,
        BigDecimal totalReturnPct,
        BigDecimal maxDrawdownPct,
        BigDecimal sharpeRatio,
        OwnedLeaderboardEvidence viewerEvidence) {}
