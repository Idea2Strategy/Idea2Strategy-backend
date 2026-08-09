package com.idea2strategy.backend.application.botoperations;

import java.time.Instant;
import java.util.UUID;

public interface BotDeletionCommandPort {
    BotDeletionResult deleteOwnedStopped(UUID botId, UUID ownerAccountId, Instant deletedAt);
}
