package com.idea2strategy.backend.domain.botcontrol;

import com.idea2strategy.backend.domain.strategy.StrategyMode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Bot(
        UUID id,
        UUID ownerAccountId,
        StrategyMode mode,
        String name,
        BotLifecycleStatus lifecycleStatus,
        Instant lifecycleChangedAt,
        Instant createdAt,
        Instant executionEligibleFrom,
        Instant startedAt,
        Instant stopRequestedAt,
        Instant stoppedAt,
        String stopReasonCode,
        long editSequence,
        Instant updatedAt) {

    public Bot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerAccountId, "ownerAccountId");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(lifecycleStatus, "lifecycleStatus");
        Objects.requireNonNull(lifecycleChangedAt, "lifecycleChangedAt");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(executionEligibleFrom, "executionEligibleFrom");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (name.isBlank() || name.length() > 120) {
            throw new IllegalArgumentException("Bot name must contain 1..120 characters");
        }
    }

    public static Bot startBasic(UUID id, UUID ownerAccountId, String name, Instant now) {
        return new Bot(
                id,
                ownerAccountId,
                StrategyMode.BASIC,
                name,
                BotLifecycleStatus.RUNNING,
                now,
                now,
                now,
                now,
                null,
                null,
                null,
                0,
                now);
    }

    public Bot requestStop(Instant now, String reasonCode) {
        if (lifecycleStatus != BotLifecycleStatus.RUNNING) {
            throw new IllegalStateException("Only a running bot can request stop");
        }
        return new Bot(
                id,
                ownerAccountId,
                mode,
                name,
                BotLifecycleStatus.STOPPING,
                now,
                createdAt,
                executionEligibleFrom,
                startedAt,
                now,
                null,
                Objects.requireNonNull(reasonCode, "reasonCode"),
                editSequence,
                now);
    }
}
