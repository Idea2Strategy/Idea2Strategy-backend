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
        Instant completedAt) {
    public OwnedStrategyValidationCatalogItem {
        Objects.requireNonNull(validationRunId, "validationRunId");
        Objects.requireNonNull(strategyId, "strategyId");
        Objects.requireNonNull(strategyName, "strategyName");
        Objects.requireNonNull(semanticHash, "semanticHash");
        Objects.requireNonNull(elementCatalogVersionId, "elementCatalogVersionId");
        Objects.requireNonNull(completedAt, "completedAt");
        if (requestedEditSequence < 0) {
            throw new IllegalArgumentException("requestedEditSequence must not be negative");
        }
    }
}
