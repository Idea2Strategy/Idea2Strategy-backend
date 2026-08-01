package com.idea2strategy.backend.application.botcontrol;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record BotContinuationFacts(UUID botId, Instant dueAt, Instant lastRenewedAt) {
    public BotContinuationFacts {
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(dueAt, "dueAt");
        if (lastRenewedAt != null && !dueAt.isAfter(lastRenewedAt)) {
            throw new IllegalArgumentException("dueAt must be after lastRenewedAt");
        }
    }
}
