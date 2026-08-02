package com.idea2strategy.backend.application.delegation;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public record DelegatedAuthorizationMutation(
        DelegatedAuthorizationCommandType commandType,
        UUID authorizationId,
        UUID accountId,
        long authorizationVersion,
        UUID replacesAuthorizationId,
        DelegatedAuthorizationStatus status,
        long authEpochAtGrant,
        String clientLabel,
        UUID disclosurePolicyDocumentId,
        Set<DelegatedAuthorizationScope> scopes,
        Set<UUID> targetStrategyIds,
        Instant expiresAt,
        Instant predecessorRevokedAt,
        String reasonCode,
        UUID credentialId,
        String credentialDigest,
        Short digestKeyVersion,
        Instant occurredAt,
        UUID correlationId) {
    public DelegatedAuthorizationMutation {
        Objects.requireNonNull(commandType, "commandType");
        Objects.requireNonNull(authorizationId, "authorizationId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(clientLabel, "clientLabel");
        Objects.requireNonNull(disclosurePolicyDocumentId, "disclosurePolicyDocumentId");
        scopes = Set.copyOf(Objects.requireNonNull(scopes, "scopes"));
        targetStrategyIds = Set.copyOf(Objects.requireNonNull(targetStrategyIds, "targetStrategyIds"));
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(correlationId, "correlationId");
    }

    public DelegatedAuthorizationResult toStoredResult() {
        return new DelegatedAuthorizationResult(
                authorizationId, authorizationVersion, status, credentialId, expiresAt, Optional.empty());
    }
}
