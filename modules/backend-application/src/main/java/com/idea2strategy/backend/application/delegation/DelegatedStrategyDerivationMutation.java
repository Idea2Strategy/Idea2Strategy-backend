package com.idea2strategy.backend.application.delegation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record DelegatedStrategyDerivationMutation(
        DelegatedStrategyDerivationType derivationType,
        UUID authorizationId,
        UUID credentialId,
        long authorizationVersion,
        UUID sourceStrategyId,
        UUID resultStrategyId,
        UUID ownerAccountId,
        long resultStrategyAccessEpoch,
        UUID correlationId,
        String idempotencyKey,
        String requestHash,
        Instant createdAt) {
    public DelegatedStrategyDerivationMutation {
        Objects.requireNonNull(derivationType, "derivationType");
        Objects.requireNonNull(authorizationId, "authorizationId");
        Objects.requireNonNull(credentialId, "credentialId");
        Objects.requireNonNull(resultStrategyId, "resultStrategyId");
        Objects.requireNonNull(ownerAccountId, "ownerAccountId");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(requestHash, "requestHash");
        Objects.requireNonNull(createdAt, "createdAt");
    }

    public DelegatedStrategyDerivationResult toResult() {
        return new DelegatedStrategyDerivationResult(
                derivationType, authorizationId, sourceStrategyId, resultStrategyId, createdAt);
    }
}
