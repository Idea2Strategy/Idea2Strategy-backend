package com.idea2strategy.backend.application.delegation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record DelegatedAuthorizationSnapshot(
        UUID authorizationId,
        UUID accountId,
        long authorizationVersion,
        DelegatedAuthorizationStatus status,
        long authEpochAtGrant,
        Instant expiresAt,
        Instant revokedAt) {
    public DelegatedAuthorizationSnapshot {
        Objects.requireNonNull(authorizationId, "authorizationId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(status, "status");
        if (authorizationVersion < 1 || authEpochAtGrant < 1) {
            throw new IllegalArgumentException("authorization version and epoch must be positive");
        }
    }
}
