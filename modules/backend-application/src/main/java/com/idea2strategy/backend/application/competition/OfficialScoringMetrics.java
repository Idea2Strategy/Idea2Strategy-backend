package com.idea2strategy.backend.application.competition;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

public record OfficialScoringMetrics(
        BigDecimal totalReturnPct,
        BigDecimal maxDrawdownPct,
        BigDecimal sharpeRatio) {
    public OfficialScoringMetrics {
        totalReturnPct = normalized(totalReturnPct, "totalReturnPct");
        maxDrawdownPct = normalized(maxDrawdownPct, "maxDrawdownPct");
        sharpeRatio = sharpeRatio == null ? null : normalized(sharpeRatio, "sharpeRatio");
    }

    private static BigDecimal normalized(BigDecimal value, String field) {
        return Objects.requireNonNull(value, field).setScale(8, RoundingMode.HALF_EVEN);
    }
}
