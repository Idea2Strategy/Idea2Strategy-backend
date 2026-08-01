package com.idea2strategy.backend.application.botcontrol;

import java.util.Optional;
import java.util.UUID;

@FunctionalInterface
public interface BotContinuationQueryPort {
    Optional<BotContinuationFacts> findOwned(UUID botId, UUID ownerAccountId);
}
