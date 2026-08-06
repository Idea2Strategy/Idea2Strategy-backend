package com.idea2strategy.backend.application.botoperations;

import com.idea2strategy.backend.domain.botcontrol.BotLifecycleStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.List;
import java.util.UUID;

public record BotOperationsProjection(
        UUID botId,
        String name,
        BotLifecycleStatus lifecycleStatus,
        Instant lifecycleChangedAt,
        Instant executionEligibleFrom,
        Instant executionBlockedAt,
        String executionBlockReasonCode,
        long lastEventSequence,
        List<BotOperationsInstrument> instruments) {
    public BotOperationsProjection {
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(lifecycleStatus, "lifecycleStatus");
        Objects.requireNonNull(lifecycleChangedAt, "lifecycleChangedAt");
        Objects.requireNonNull(executionEligibleFrom, "executionEligibleFrom");
        if (lastEventSequence < 0) {
            throw new IllegalArgumentException("lastEventSequence must not be negative");
        }
        instruments = List.copyOf(Objects.requireNonNull(instruments, "instruments"));
    }
}
