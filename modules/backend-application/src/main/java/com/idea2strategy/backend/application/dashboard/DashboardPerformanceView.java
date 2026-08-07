package com.idea2strategy.backend.application.dashboard;

import java.math.BigDecimal;
import java.time.Instant;

public record DashboardPerformanceView(
        BigDecimal equityAmount,
        BigDecimal totalReturnPct,
        BigDecimal maxDrawdownPct,
        BigDecimal sharpeRatio,
        String calculationRulesVersion,
        Instant updatedAt) {}
