package com.idea2strategy.backend.domain.performance;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record BotCurrentPerformance(
        UUID botId,
        BigDecimal equityAmount,
        BigDecimal totalReturnPct,
        BigDecimal maxDrawdownPct,
        BigDecimal sharpeRatio,
        String metricsDocument,
        String ledgerStateHash,
        String positionStateHash,
        String calculationRulesVersion,
        long lastEventSequence,
        String projectionHash,
        Instant updatedAt) {

    public BotCurrentPerformance {
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(equityAmount, "equityAmount");
        Objects.requireNonNull(totalReturnPct, "totalReturnPct");
        Objects.requireNonNull(maxDrawdownPct, "maxDrawdownPct");
        Objects.requireNonNull(metricsDocument, "metricsDocument");
        Objects.requireNonNull(ledgerStateHash, "ledgerStateHash");
        Objects.requireNonNull(positionStateHash, "positionStateHash");
        Objects.requireNonNull(calculationRulesVersion, "calculationRulesVersion");
        Objects.requireNonNull(projectionHash, "projectionHash");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (lastEventSequence < 0) {
            throw new IllegalArgumentException("lastEventSequence must not be negative");
        }
    }
}
