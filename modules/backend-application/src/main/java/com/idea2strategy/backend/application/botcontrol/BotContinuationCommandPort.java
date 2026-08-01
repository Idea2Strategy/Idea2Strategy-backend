package com.idea2strategy.backend.application.botcontrol;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@FunctionalInterface
public interface BotContinuationCommandPort {
    Optional<BotContinuationFacts> renewOwned(UUID botId, UUID ownerAccountId, Instant receivedAt);
}
