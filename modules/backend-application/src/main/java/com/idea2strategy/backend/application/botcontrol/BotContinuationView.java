package com.idea2strategy.backend.application.botcontrol;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record BotContinuationView(
        UUID botId,
        Instant dueAt,
        Instant renewalAvailableFrom,
        Instant lastRenewedAt,
        boolean renewalAllowed) {
    public BotContinuationView {
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(dueAt, "dueAt");
        Objects.requireNonNull(renewalAvailableFrom, "renewalAvailableFrom");
    }
}
