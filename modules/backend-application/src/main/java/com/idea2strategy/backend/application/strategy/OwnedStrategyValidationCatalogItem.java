package com.idea2strategy.backend.application.strategy;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record OwnedStrategyValidationCatalogItem(
        UUID validationRunId,
        UUID strategyId,
        String strategyName,
        long requestedEditSequence,
        String semanticHash,
        UUID elementCatalogVersionId,
        String languageVersion,
        String schemaVersion,
        String catalogVersion,
        Instant completedAt) {
    public OwnedStrategyValidationCatalogItem {
        Objects.requireNonNull(validationRunId, "validationRunId");
        Objects.requireNonNull(strategyId, "strategyId");
        Objects.requireNonNull(strategyName, "strategyName");
        Objects.requireNonNull(semanticHash, "semanticHash");
        Objects.requireNonNull(elementCatalogVersionId, "elementCatalogVersionId");
        requireText(languageVersion, "languageVersion");
        requireText(schemaVersion, "schemaVersion");
        requireText(catalogVersion, "catalogVersion");
        Objects.requireNonNull(completedAt, "completedAt");
        if (requestedEditSequence < 0) {
            throw new IllegalArgumentException("requestedEditSequence must not be negative");
        }
    }

    private static void requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
