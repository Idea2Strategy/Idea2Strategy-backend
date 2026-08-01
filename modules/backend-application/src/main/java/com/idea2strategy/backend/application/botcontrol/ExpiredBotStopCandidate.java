package com.idea2strategy.backend.application.botcontrol;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ExpiredBotStopCandidate(UUID botId, UUID ownerAccountId, Instant dueAt) {
    public ExpiredBotStopCandidate {
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(ownerAccountId, "ownerAccountId");
        Objects.requireNonNull(dueAt, "dueAt");
    }
}
