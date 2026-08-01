package com.idea2strategy.backend.application.botcontrol;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record BotExecutionPreflightFacts(
        UUID botId,
        BigDecimal initialCashAmount,
        int projectedConcurrentExecutionCount,
        List<UUID> unsupportedInstrumentIds,
        boolean feePolicyActive,
        boolean buyingPowerBufferPolicyActive,
        boolean riskPolicyConfigured,
        List<DataRequirement> unavailableDataRequirements) {

    public BotExecutionPreflightFacts {
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(initialCashAmount, "initialCashAmount");
        if (projectedConcurrentExecutionCount < 0) {
            throw new IllegalArgumentException("projectedConcurrentExecutionCount must not be negative");
        }
        unsupportedInstrumentIds = List.copyOf(Objects.requireNonNull(
                unsupportedInstrumentIds, "unsupportedInstrumentIds"));
        unavailableDataRequirements = List.copyOf(Objects.requireNonNull(
                unavailableDataRequirements, "unavailableDataRequirements"));
    }

    public record DataRequirement(UUID instrumentId, UUID featureDefinitionId) {
        public DataRequirement {
            Objects.requireNonNull(instrumentId, "instrumentId");
            Objects.requireNonNull(featureDefinitionId, "featureDefinitionId");
        }
    }
}
