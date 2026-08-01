package com.idea2strategy.backend.application.strategy;

import com.idea2strategy.backend.domain.strategy.StrategyDocument;
import java.util.Optional;
import java.util.UUID;

public interface StrategyDocumentQueryPort {
    Optional<StrategyDocument> findOwnedByStrategyId(UUID strategyId, UUID ownerAccountId);
}
