package com.idea2strategy.backend.application.strategy;

import com.idea2strategy.backend.domain.strategy.StrategyValidationRun;
import java.util.Optional;
import java.util.UUID;

public interface StrategyValidationRunQueryPort {
    Optional<StrategyValidationRun> findOwnedById(UUID validationRunId, UUID ownerAccountId);
}
