package com.idea2strategy.backend.application.strategy;

import com.idea2strategy.backend.domain.strategy.StrategyValidationRun;

public interface StrategyValidationRunCommandPort {
    void save(StrategyValidationRun run);
}
