package com.idea2strategy.backend.application.competition;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record VirtualLiquidationPerformance(
        UUID snapshotId,
        UUID botId,
        UUID evaluationSegmentId,
        long sourceEventSequence,
        Instant evaluatedAt,
        BigDecimal equityAmount,
        BigDecimal totalReturnPct,
        BigDecimal maxDrawdownPct,
        BigDecimal sharpeRatio,
        String metricsDocument,
        String inputHash,
        String calculationRulesVersion,
        String snapshotHash,
        String finalStateHash,
        String sourceSetHash,
        String virtualLiquidationDocument) {

    public VirtualLiquidationPerformance {
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(evaluationSegmentId, "evaluationSegmentId");
        Objects.requireNonNull(evaluatedAt, "evaluatedAt");
        Objects.requireNonNull(equityAmount, "equityAmount");
        Objects.requireNonNull(totalReturnPct, "totalReturnPct");
        Objects.requireNonNull(maxDrawdownPct, "maxDrawdownPct");
        Objects.requireNonNull(metricsDocument, "metricsDocument");
        VirtualLiquidationContext.requireHash(inputHash, "inputHash");
        Objects.requireNonNull(calculationRulesVersion, "calculationRulesVersion");
        VirtualLiquidationContext.requireHash(snapshotHash, "snapshotHash");
        VirtualLiquidationContext.requireHash(finalStateHash, "finalStateHash");
        VirtualLiquidationContext.requireHash(sourceSetHash, "sourceSetHash");
        Objects.requireNonNull(virtualLiquidationDocument, "virtualLiquidationDocument");
        if (sourceEventSequence < 0) {
            throw new IllegalArgumentException("sourceEventSequence must not be negative");
        }
    }
}
