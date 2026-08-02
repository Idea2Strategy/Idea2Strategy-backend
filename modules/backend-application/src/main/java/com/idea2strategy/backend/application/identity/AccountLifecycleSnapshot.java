package com.idea2strategy.backend.application.identity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AccountLifecycleSnapshot(
        UUID accountId,
        AccountLifecycleStatus status,
        long version,
        Instant lastSuccessfulAuthAt,
        Instant withdrawalRequestedAt,
        Instant cancellationDeadlineAt,
        AccountLifecycleStatus closingPreviousStatus) {
    public AccountLifecycleSnapshot {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(status, "status");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
    }
}
