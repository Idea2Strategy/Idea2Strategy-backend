package com.idea2strategy.backend.application.performance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.idea2strategy.backend.domain.competition.CompetitionType;
import com.idea2strategy.backend.domain.performance.BotCurrentPerformance;
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

public final class LivePerformanceProjectionCalculator {
    private static final int METRIC_SCALE = 8;
    private final ObjectMapper objectMapper;

    public LivePerformanceProjectionCalculator() {
        this(JsonMapper.builder()
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .build());
    }

    LivePerformanceProjectionCalculator(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    public OfficialLivePerformanceProjection calculate(LivePerformanceProjectionInput input) {
        Objects.requireNonNull(input, "input");
        if (input.competitionType() != CompetitionType.LIVE_PAPER
                || input.source() != LivePerformanceSource.LIVE_TRADING) {
            throw new IllegalArgumentException("BACKTEST performance must not enter the live projection");
        }

        BigDecimal initialCapital = normalized(input.initialCapitalAmount(), "initialCapitalAmount");
        if (initialCapital.signum() <= 0) {
            throw new IllegalArgumentException("initialCapitalAmount must be positive");
        }
        BigDecimal currentCash = nonnegative(input.currentCashAmount(), "currentCashAmount");
        List<BigDecimal> positions = input.positionMarketValues().stream()
                .map(value -> nonnegative(value, "positionMarketValues"))
                .sorted()
                .toList();
        BigDecimal equity = positions.stream().reduce(currentCash, BigDecimal::add).setScale(METRIC_SCALE);
        List<EquityObservation> history = normalizedHistory(input, equity);
        BigDecimal totalReturn = equity.subtract(initialCapital)
                .multiply(BigDecimal.valueOf(100))
                .divide(initialCapital, METRIC_SCALE, RoundingMode.HALF_EVEN);
        BigDecimal maxDrawdown = maxDrawdown(initialCapital, history);
        BigDecimal sharpe = input.producerCalculatedSharpeRatio() == null
                ? null
                : normalized(input.producerCalculatedSharpeRatio(), "producerCalculatedSharpeRatio");
        String metricsDocument = canonicalMetrics(input);
        String projectionHash = projectionHash(
                input,
                initialCapital,
                currentCash,
                positions,
                history,
                equity,
                totalReturn,
                maxDrawdown,
                sharpe,
                metricsDocument);

        return new OfficialLivePerformanceProjection(
                input.competitionType(),
                input.source(),
                new BotCurrentPerformance(
                        input.botId(),
                        equity,
                        totalReturn,
                        maxDrawdown,
                        sharpe,
                        metricsDocument,
                        input.ledgerStateHash(),
                        input.positionStateHash(),
                        input.calculationRulesVersion(),
                        input.sourceEventSequence(),
                        projectionHash,
                        input.occurredAt()));
    }

    private String canonicalMetrics(LivePerformanceProjectionInput input) {
        try {
            return objectMapper.writeValueAsString(input.additionalMetrics());
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("metrics must be JSON serializable", exception);
        }
    }

    private String projectionHash(
            LivePerformanceProjectionInput input,
            BigDecimal initialCapital,
            BigDecimal currentCash,
            List<BigDecimal> positions,
            List<EquityObservation> history,
            BigDecimal equity,
            BigDecimal totalReturn,
            BigDecimal maxDrawdown,
            BigDecimal sharpe,
            String metricsDocument) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("projectionSchemaVersion", "live-performance-projection.v1");
        canonical.put("botId", input.botId().toString());
        canonical.put("competitionType", input.competitionType().name());
        canonical.put("source", input.source().name());
        canonical.put("initialCapitalAmount", initialCapital.toPlainString());
        canonical.put("currentCashAmount", currentCash.toPlainString());
        canonical.put("positionMarketValues", positions.stream().map(BigDecimal::toPlainString).toList());
        List<Map<String, Object>> canonicalHistory = new ArrayList<>();
        for (EquityObservation observation : history) {
            canonicalHistory.add(Map.of(
                    "sourceEventSequence", observation.sourceEventSequence(),
                    "equityAmount", observation.equityAmount().toPlainString()));
        }
        canonical.put("equityHistory", canonicalHistory);
        canonical.put("equityAmount", equity.toPlainString());
        canonical.put("totalReturnPct", totalReturn.toPlainString());
        canonical.put("maxDrawdownPct", maxDrawdown.toPlainString());
        canonical.put("producerCalculatedSharpeRatio", sharpe == null ? null : sharpe.toPlainString());
        canonical.put("additionalMetrics", input.additionalMetrics());
        canonical.put("ledgerStateHash", input.ledgerStateHash());
        canonical.put("positionStateHash", input.positionStateHash());
        canonical.put("calculationRulesVersion", input.calculationRulesVersion());
        canonical.put("sourceEventSequence", input.sourceEventSequence());
        canonical.put("occurredAt", input.occurredAt().toString());
        try {
            String canonicalJson = objectMapper.writeValueAsString(canonical);
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("canonical performance evidence is not JSON serializable", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static List<EquityObservation> normalizedHistory(
            LivePerformanceProjectionInput input,
            BigDecimal currentEquity) {
        if (input.equityHistory().isEmpty()) {
            throw new IllegalArgumentException("equityHistory must contain the current event observation");
        }
        List<EquityObservation> normalized = new ArrayList<>(input.equityHistory().size());
        long previousSequence = -1;
        for (EquityObservation observation : input.equityHistory()) {
            if (observation.sourceEventSequence() <= previousSequence) {
                throw new IllegalArgumentException("equityHistory source event sequences must be strictly increasing");
            }
            BigDecimal equity = nonnegative(observation.equityAmount(), "equityHistory.equityAmount");
            normalized.add(new EquityObservation(observation.sourceEventSequence(), equity));
            previousSequence = observation.sourceEventSequence();
        }
        EquityObservation last = normalized.getLast();
        if (last.sourceEventSequence() != input.sourceEventSequence()
                || last.equityAmount().compareTo(currentEquity) != 0) {
            throw new IllegalArgumentException("latest equity observation must match the current ledger and position facts");
        }
        return List.copyOf(normalized);
    }

    private static BigDecimal maxDrawdown(
            BigDecimal initialCapital,
            List<EquityObservation> history) {
        BigDecimal peak = initialCapital;
        BigDecimal maximum = BigDecimal.ZERO.setScale(METRIC_SCALE);
        for (EquityObservation observation : history) {
            BigDecimal equity = observation.equityAmount();
            if (equity.compareTo(peak) > 0) {
                peak = equity;
            } else if (peak.signum() > 0) {
                BigDecimal drawdown = peak.subtract(equity)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(peak, METRIC_SCALE, RoundingMode.HALF_EVEN);
                maximum = maximum.max(drawdown);
            }
        }
        return maximum;
    }

    private static BigDecimal nonnegative(BigDecimal value, String field) {
        BigDecimal normalized = normalized(Objects.requireNonNull(value, field), field);
        if (normalized.signum() < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        return normalized;
    }

    private static BigDecimal normalized(BigDecimal value, String field) {
        try {
            return value.setScale(METRIC_SCALE, RoundingMode.HALF_EVEN);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(field + " cannot be normalized", exception);
        }
    }
}
