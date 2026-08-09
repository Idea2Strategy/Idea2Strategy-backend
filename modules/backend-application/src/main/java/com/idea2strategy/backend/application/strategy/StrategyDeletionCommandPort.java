package com.idea2strategy.backend.application.strategy;

import java.time.Instant;
import java.util.UUID;

public interface StrategyDeletionCommandPort {
    StrategyDeletionResult deleteOwned(UUID strategyId, UUID ownerAccountId, Instant deletedAt);
}
