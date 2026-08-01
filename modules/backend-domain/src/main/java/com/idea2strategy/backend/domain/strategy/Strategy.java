package com.idea2strategy.backend.domain.strategy;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Strategy(
        UUID id,
        UUID ownerAccountId,
        StrategyMode mode,
        String name,
        String description,
        long editSequence,
        Instant createdAt,
        Instant updatedAt) {

    public Strategy {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerAccountId, "ownerAccountId");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (name.isBlank() || name.length() > 120) {
            throw new IllegalArgumentException("Strategy name must contain 1..120 characters");
        }
        if (editSequence < 0) {
            throw new IllegalArgumentException("editSequence must not be negative");
        }
    }

    public static Strategy createBasic(
            UUID id, UUID ownerAccountId, String name, String description, Instant now) {
        return new Strategy(id, ownerAccountId, StrategyMode.BASIC, name, description, 0, now, now);
    }
}
