package com.idea2strategy.backend.domain.strategy;

import java.util.Objects;
import java.util.UUID;

public record StrategyFeatureDefinition(
        UUID id,
        UUID catalogId,
        String featureCode,
        String calculatorVersion,
        String resolution,
        String normalizedParameters,
        String outputValueType,
        int requiredHistoryPoints,
        String definitionHash) {
    public StrategyFeatureDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(catalogId, "catalogId");
        featureCode = requireText(featureCode, "featureCode");
        calculatorVersion = requireText(calculatorVersion, "calculatorVersion");
        resolution = requireText(resolution, "resolution");
        normalizedParameters = requireText(normalizedParameters, "normalizedParameters");
        outputValueType = requireText(outputValueType, "outputValueType");
        definitionHash = requireText(definitionHash, "definitionHash");
        if (requiredHistoryPoints < 0) {
            throw new IllegalArgumentException("requiredHistoryPoints must not be negative");
        }
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
