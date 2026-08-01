package com.idea2strategy.backend.application.strategy;

import com.idea2strategy.backend.domain.strategy.StrategyValidationFreshness;
import com.idea2strategy.backend.domain.strategy.StrategyValidationRun;
import java.util.Objects;

public record StrategyValidationSnapshot(
        StrategyValidationRun run,
        StrategyValidationFreshness freshness) {
    public StrategyValidationSnapshot {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(freshness, "freshness");
    }
}
