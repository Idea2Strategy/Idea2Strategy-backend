package com.idea2strategy.backend.application.competition;

import com.idea2strategy.backend.application.performance.EquityObservation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record VirtualLiquidationQuote(
        UUID roomId,
        UUID participationId,
        UUID botId,
        UUID evaluationSegmentId,
        Instant cutoffAt,
        long sourceEventSequence,
        BigDecimal currentCashAmount,
        BigDecimal netLiquidationCashDelta,
        List<EquityObservation> equityHistory,
        BigDecimal producerCalculatedSharpeRatio,
        int liquidatedPositionCount,
        BigDecimal grossProceedsAmount,
        BigDecimal grossCostAmount,
        BigDecimal feeAmount,
        UUID feePolicyId,
        String feeRulesHash,
        int slippageRateBps,
        String ledgerStateHash,
        String positionStateHash,
        String sourceSetHash,
        String quoteContractVersion,
        String quoteRulesVersion,
        String quoteHash) {
    public static final String CONTRACT_VERSION = "virtual-liquidation-quote.v1";
    private static final Pattern RULES_VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,79}");

    public VirtualLiquidationQuote {
        Objects.requireNonNull(roomId, "roomId");
        Objects.requireNonNull(participationId, "participationId");
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(evaluationSegmentId, "evaluationSegmentId");
        Objects.requireNonNull(cutoffAt, "cutoffAt");
        Objects.requireNonNull(currentCashAmount, "currentCashAmount");
        Objects.requireNonNull(netLiquidationCashDelta, "netLiquidationCashDelta");
        equityHistory = Objects.requireNonNull(equityHistory, "equityHistory").stream()
                .map(observation -> Objects.requireNonNull(observation, "equityHistory item"))
                .sorted(Comparator.comparingLong(EquityObservation::sourceEventSequence))
                .toList();
        Objects.requireNonNull(grossProceedsAmount, "grossProceedsAmount");
        Objects.requireNonNull(grossCostAmount, "grossCostAmount");
        Objects.requireNonNull(feeAmount, "feeAmount");
        Objects.requireNonNull(feePolicyId, "feePolicyId");
        feeRulesHash = VirtualLiquidationContext.requireHash(feeRulesHash, "feeRulesHash");
        ledgerStateHash = VirtualLiquidationContext.requireHash(ledgerStateHash, "ledgerStateHash");
        positionStateHash = VirtualLiquidationContext.requireHash(positionStateHash, "positionStateHash");
        sourceSetHash = VirtualLiquidationContext.requireHash(sourceSetHash, "sourceSetHash");
        quoteHash = VirtualLiquidationContext.requireHash(quoteHash, "quoteHash");
        if (!CONTRACT_VERSION.equals(quoteContractVersion)) {
            throw new IllegalArgumentException("unsupported quoteContractVersion");
        }
        if (quoteRulesVersion == null || !RULES_VERSION.matcher(quoteRulesVersion).matches()) {
            throw new IllegalArgumentException("quoteRulesVersion is invalid");
        }
        if (sourceEventSequence < 0 || liquidatedPositionCount < 0 || slippageRateBps < 0) {
            throw new IllegalArgumentException("quote counts and sequence must not be negative");
        }
        if (grossProceedsAmount.signum() < 0 || grossCostAmount.signum() < 0 || feeAmount.signum() < 0) {
            throw new IllegalArgumentException("quote monetary aggregates must not be negative");
        }
        if (liquidatedPositionCount == 0
                && (grossProceedsAmount.signum() != 0 || grossCostAmount.signum() != 0
                        || feeAmount.signum() != 0 || netLiquidationCashDelta.signum() != 0)) {
            throw new IllegalArgumentException("an empty liquidation cannot contain cash movements");
        }
    }

    public VirtualLiquidationQuote withQuoteHash(String replacement) {
        return new VirtualLiquidationQuote(
                roomId, participationId, botId, evaluationSegmentId, cutoffAt, sourceEventSequence,
                currentCashAmount, netLiquidationCashDelta, equityHistory, producerCalculatedSharpeRatio,
                liquidatedPositionCount, grossProceedsAmount, grossCostAmount, feeAmount, feePolicyId,
                feeRulesHash, slippageRateBps, ledgerStateHash, positionStateHash, sourceSetHash,
                quoteContractVersion, quoteRulesVersion, replacement);
    }
}
