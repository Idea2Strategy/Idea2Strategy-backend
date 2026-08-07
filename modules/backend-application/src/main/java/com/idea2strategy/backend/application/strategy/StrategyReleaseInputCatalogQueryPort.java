package com.idea2strategy.backend.application.strategy;

import java.time.Instant;

public interface StrategyReleaseInputCatalogQueryPort {
    StrategyReleaseInputCatalog findSelectableAt(Instant observedAt);
}
