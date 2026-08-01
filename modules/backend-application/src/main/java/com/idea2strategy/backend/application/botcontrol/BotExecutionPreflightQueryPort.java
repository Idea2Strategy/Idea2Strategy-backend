package com.idea2strategy.backend.application.botcontrol;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface BotExecutionPreflightQueryPort {
    Optional<BotExecutionPreflightFacts> findOwnedById(UUID botId, UUID ownerAccountId, Instant evaluatedAt);
}
