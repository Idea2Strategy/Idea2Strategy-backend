package com.idea2strategy.backend.application.competition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.idea2strategy.backend.application.performance.EquityObservation;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class VirtualLiquidationPerformanceCalculator {
    public static final String CALCULATION_RULES_VERSION = "virtual-liquidation-performance.v1";
    private static final int SCALE = 8;
    private final ObjectMapper objectMapper;

    public VirtualLiquidationPerformanceCalculator() {
        this(JsonMapper.builder()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .build());
    }

    VirtualLiquidationPerformanceCalculator(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public VirtualLiquidationPerformance calculate(
            VirtualLiquidationContext context,
            VirtualLiquidationQuote quote) {
        validateBoundary(Objects.requireNonNull(context, "context"), Objects.requireNonNull(quote, "quote"));
        if (!VirtualLiquidationQuoteHasher.matches(quote)) {
            throw new VirtualLiquidationConflictException("virtual liquidation quote hash does not match its payload");
        }
        BigDecimal initial = normalized(context.initialCapitalAmount());
        BigDecimal currentCash = normalized(quote.currentCashAmount());
        BigDecimal cashDelta = normalized(quote.netLiquidationCashDelta());
        BigDecimal preservedDelta = normalized(quote.grossProceedsAmount())
                .subtract(normalized(quote.grossCostAmount()))
                .subtract(normalized(quote.feeAmount()))
                .setScale(SCALE);
        if (cashDelta.compareTo(preservedDelta) != 0) {
            throw new VirtualLiquidationConflictException(
                    "net liquidation cash delta does not preserve proceeds minus cost and fee");
        }
        BigDecimal finalEquity = currentCash.add(cashDelta).setScale(SCALE);
        List<EquityObservation> history = history(context, quote, finalEquity);
        BigDecimal totalReturn = finalEquity.subtract(initial)
                .multiply(BigDecimal.valueOf(100))
                .divide(initial, SCALE, RoundingMode.HALF_EVEN);
        BigDecimal maxDrawdown = maxDrawdown(initial, history);
        BigDecimal sharpe = quote.producerCalculatedSharpeRatio() == null
                ? null : normalized(quote.producerCalculatedSharpeRatio());

        Map<String, Object> aggregate = aggregateDocument(quote, currentCash, cashDelta, finalEquity);
        String virtualDocument = json(aggregate);
        String metricsDocument = json(Map.of(
                "virtualLiquidation", true,
                "liquidatedPositionCount", quote.liquidatedPositionCount(),
                "quoteHash", quote.quoteHash()));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("schemaVersion", CALCULATION_RULES_VERSION);
        input.put("roomId", context.roomId().toString());
        input.put("participationId", context.participationId().toString());
        input.put("botId", context.botId().toString());
        input.put("evaluationSegmentId", context.evaluationSegmentId().toString());
        input.put("startsAt", context.startsAt().toString());
        input.put("endsAt", context.endsAt().toString());
        input.put("startEventSequence", context.startEventSequence());
        input.put("initialCapitalAmount", initial.toPlainString());
        input.put("feePolicyId", context.feePolicyId().toString());
        input.put("feeRulesHash", context.feeRulesHash());
        input.put("slippageRateBps", context.slippageRateBps());
        input.put("roomRulesHash", context.roomRulesHash());
        input.put("quote", aggregate);
        input.put("equityHistory", history.stream().map(observation -> Map.of(
                "sourceEventSequence", observation.sourceEventSequence(),
                "equityAmount", normalized(observation.equityAmount()).toPlainString())).toList());
        input.put("ledgerStateHash", quote.ledgerStateHash());
        input.put("positionStateHash", quote.positionStateHash());
        input.put("sourceSetHash", quote.sourceSetHash());
        input.put("quoteContractVersion", quote.quoteContractVersion());
        input.put("quoteRulesVersion", quote.quoteRulesVersion());
        input.put("quoteHash", quote.quoteHash());
        String inputHash = hash(input);

        Map<String, Object> finalState = new LinkedHashMap<>();
        finalState.put("schemaVersion", "virtual-liquidation-final-state.v1");
        finalState.put("inputHash", inputHash);
        finalState.put("quoteHash", quote.quoteHash());
        finalState.put("aggregate", aggregate);
        finalState.put("finalEquityAmount", finalEquity.toPlainString());
        finalState.put("ledgerStateHash", quote.ledgerStateHash());
        finalState.put("positionStateHash", quote.positionStateHash());
        finalState.put("sourceSetHash", quote.sourceSetHash());
        finalState.put("sourceEventSequence", quote.sourceEventSequence());
        finalState.put("cutoffAt", quote.cutoffAt().toString());
        String finalStateHash = hash(finalState);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schemaVersion", "virtual-liquidation-snapshot.v1");
        snapshot.put("inputHash", inputHash);
        snapshot.put("equityAmount", finalEquity.toPlainString());
        snapshot.put("totalReturnPct", totalReturn.toPlainString());
        snapshot.put("maxDrawdownPct", maxDrawdown.toPlainString());
        snapshot.put("sharpeRatio", sharpe == null ? null : sharpe.toPlainString());
        snapshot.put("metricsDocument", metricsDocument);
        snapshot.put("finalStateHash", finalStateHash);
        snapshot.put("sourceSetHash", quote.sourceSetHash());
        String snapshotHash = hash(snapshot);

        UUID snapshotId = UUID.nameUUIDFromBytes(
                ("virtual-liquidation-snapshot.v1:" + context.evaluationSegmentId())
                        .getBytes(StandardCharsets.UTF_8));
        return new VirtualLiquidationPerformance(
                snapshotId, context.botId(), context.evaluationSegmentId(), quote.sourceEventSequence(),
                context.endsAt(), finalEquity, totalReturn, maxDrawdown, sharpe, metricsDocument,
                inputHash, CALCULATION_RULES_VERSION, snapshotHash, finalStateHash,
                quote.sourceSetHash(), virtualDocument);
    }

    private static void validateBoundary(VirtualLiquidationContext context, VirtualLiquidationQuote quote) {
        if (!context.roomId().equals(quote.roomId())
                || !context.participationId().equals(quote.participationId())
                || !context.botId().equals(quote.botId())
                || !context.evaluationSegmentId().equals(quote.evaluationSegmentId())) {
            throw new VirtualLiquidationConflictException("virtual liquidation identity boundary does not match");
        }
        if (!context.endsAt().equals(quote.cutoffAt())) {
            throw new VirtualLiquidationConflictException("virtual liquidation cutoff does not match");
        }
        if (quote.sourceEventSequence() < context.startEventSequence()) {
            throw new VirtualLiquidationConflictException("virtual liquidation source sequence precedes evaluation");
        }
        if (!context.feePolicyId().equals(quote.feePolicyId())
                || !context.feeRulesHash().equals(quote.feeRulesHash())) {
            throw new VirtualLiquidationConflictException("virtual liquidation fee policy does not match");
        }
        if (context.slippageRateBps() != quote.slippageRateBps()) {
            throw new VirtualLiquidationConflictException("virtual liquidation slippage rule does not match");
        }
    }

    private static List<EquityObservation> history(
            VirtualLiquidationContext context,
            VirtualLiquidationQuote quote,
            BigDecimal finalEquity) {
        List<EquityObservation> result = new ArrayList<>();
        long previous = -1;
        for (EquityObservation observation : quote.equityHistory()) {
            if (observation.sourceEventSequence() < context.startEventSequence()
                    || observation.sourceEventSequence() <= previous
                    || observation.sourceEventSequence() >= quote.sourceEventSequence()) {
                throw new VirtualLiquidationConflictException(
                        "equity history must be ordered before the virtual liquidation sequence");
            }
            BigDecimal equity = normalized(observation.equityAmount());
            result.add(new EquityObservation(observation.sourceEventSequence(), equity));
            previous = observation.sourceEventSequence();
        }
        result.add(new EquityObservation(quote.sourceEventSequence(), finalEquity));
        return List.copyOf(result);
    }

    private static BigDecimal maxDrawdown(BigDecimal initial, List<EquityObservation> history) {
        BigDecimal peak = initial;
        BigDecimal maximum = BigDecimal.ZERO.setScale(SCALE);
        for (EquityObservation observation : history) {
            BigDecimal equity = observation.equityAmount();
            if (equity.compareTo(peak) > 0) {
                peak = equity;
            } else {
                BigDecimal drawdown = peak.subtract(equity)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(peak, SCALE, RoundingMode.HALF_EVEN);
                maximum = maximum.max(drawdown);
            }
        }
        return maximum;
    }

    private static Map<String, Object> aggregateDocument(
            VirtualLiquidationQuote quote,
            BigDecimal currentCash,
            BigDecimal cashDelta,
            BigDecimal finalEquity) {
        Map<String, Object> aggregate = new LinkedHashMap<>();
        aggregate.put("schemaVersion", "virtual-liquidation-evidence.v1");
        aggregate.put("positionCount", quote.liquidatedPositionCount());
        aggregate.put("currentCashAmount", currentCash.toPlainString());
        aggregate.put("netLiquidationCashDelta", cashDelta.toPlainString());
        aggregate.put("finalEquityAmount", finalEquity.toPlainString());
        aggregate.put("grossProceedsAmount", normalized(quote.grossProceedsAmount()).toPlainString());
        aggregate.put("grossCostAmount", normalized(quote.grossCostAmount()).toPlainString());
        aggregate.put("feeAmount", normalized(quote.feeAmount()).toPlainString());
        aggregate.put("feePolicyId", quote.feePolicyId().toString());
        aggregate.put("feeRulesHash", quote.feeRulesHash());
        aggregate.put("slippageRateBps", quote.slippageRateBps());
        aggregate.put("quoteContractVersion", quote.quoteContractVersion());
        aggregate.put("quoteRulesVersion", quote.quoteRulesVersion());
        aggregate.put("quoteHash", quote.quoteHash());
        return Map.copyOf(aggregate);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("virtual liquidation evidence is not JSON serializable", exception);
        }
    }

    private String hash(Object value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(json(value).getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static BigDecimal normalized(BigDecimal value) {
        return Objects.requireNonNull(value, "value").setScale(SCALE, RoundingMode.HALF_EVEN);
    }
}
