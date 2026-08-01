package com.idea2strategy.backend.application.strategy;

import com.idea2strategy.backend.domain.strategy.Strategy;
import java.util.Optional;
import java.util.UUID;

public interface StrategyQueryPort {
    Optional<Strategy> findOwnedById(UUID strategyId, UUID ownerAccountId);
}
