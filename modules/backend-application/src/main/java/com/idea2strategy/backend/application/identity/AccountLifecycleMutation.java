package com.idea2strategy.backend.application.identity;

import java.time.Instant;
import java.util.Objects;

public record AccountLifecycleMutation(
        AccountLifecycleStatus status,
        Instant occurredAt,
        AccountLifecycleStatus closingPreviousStatus,
        Instant withdrawalRequestedAt,
        Instant cancellationDeadlineAt,
        String reasonCode) {
    public AccountLifecycleMutation {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (Objects.requireNonNull(reasonCode, "reasonCode").isBlank()) {
            throw new IllegalArgumentException("reasonCode must not be blank");
        }
    }

    public AccountLifecycleSnapshot applyTo(AccountLifecycleSnapshot current) {
        return new AccountLifecycleSnapshot(
                current.accountId(),
                status,
                current.version() + 1,
                current.lastSuccessfulAuthAt(),
                withdrawalRequestedAt,
                cancellationDeadlineAt,
                closingPreviousStatus);
    }
}
