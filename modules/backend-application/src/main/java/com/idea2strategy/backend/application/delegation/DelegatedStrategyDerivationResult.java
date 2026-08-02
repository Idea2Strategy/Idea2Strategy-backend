package com.idea2strategy.backend.application.delegation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record DelegatedStrategyDerivationResult(
        DelegatedStrategyDerivationType derivationType,
        UUID authorizationId,
        UUID sourceStrategyId,
        UUID resultStrategyId,
        Instant createdAt) {
    public DelegatedStrategyDerivationResult {
        Objects.requireNonNull(derivationType, "derivationType");
        Objects.requireNonNull(authorizationId, "authorizationId");
        Objects.requireNonNull(resultStrategyId, "resultStrategyId");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
