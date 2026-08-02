package com.idea2strategy.backend.application.performance;

import com.idea2strategy.backend.domain.competition.CompetitionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record LivePerformanceProjectionInput(
        UUID botId,
        CompetitionType competitionType,
        LivePerformanceSource source,
        BigDecimal initialCapitalAmount,
        BigDecimal currentCashAmount,
        List<BigDecimal> positionMarketValues,
        List<EquityObservation> equityHistory,
        BigDecimal producerCalculatedSharpeRatio,
        Map<String, Object> additionalMetrics,
        String ledgerStateHash,
        String positionStateHash,
        String calculationRulesVersion,
        long sourceEventSequence,
        Instant occurredAt) {
    private static final Pattern SHA_256 = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern RULES_VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,79}");

    public LivePerformanceProjectionInput {
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(competitionType, "competitionType");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(initialCapitalAmount, "initialCapitalAmount");
        Objects.requireNonNull(currentCashAmount, "currentCashAmount");
        positionMarketValues = List.copyOf(Objects.requireNonNull(positionMarketValues, "positionMarketValues"));
        equityHistory = List.copyOf(Objects.requireNonNull(equityHistory, "equityHistory"));
        additionalMetrics = additionalMetrics == null ? Map.of() : Map.copyOf(additionalMetrics);
        ledgerStateHash = requireHash(ledgerStateHash, "ledgerStateHash");
        positionStateHash = requireHash(positionStateHash, "positionStateHash");
        if (calculationRulesVersion == null || !RULES_VERSION.matcher(calculationRulesVersion).matches()) {
            throw new IllegalArgumentException(
                    "calculationRulesVersion must be 1-80 letters, digits, dots, underscores, or hyphens");
        }
        if (sourceEventSequence < 0) {
            throw new IllegalArgumentException("sourceEventSequence must not be negative");
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    private static String requireHash(String value, String field) {
        if (value == null || !SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be sha256 followed by 64 lowercase hex characters");
        }
        return value;
    }
}
