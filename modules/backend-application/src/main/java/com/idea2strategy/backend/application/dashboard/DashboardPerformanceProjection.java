package com.idea2strategy.backend.application.dashboard;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record DashboardPerformanceProjection(
        BigDecimal equityAmount,
        BigDecimal totalReturnPct,
        BigDecimal maxDrawdownPct,
        BigDecimal sharpeRatio,
        String calculationRulesVersion,
        Instant updatedAt) {
    public DashboardPerformanceProjection {
        Objects.requireNonNull(equityAmount, "equityAmount");
        Objects.requireNonNull(totalReturnPct, "totalReturnPct");
        Objects.requireNonNull(maxDrawdownPct, "maxDrawdownPct");
        Objects.requireNonNull(calculationRulesVersion, "calculationRulesVersion");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
