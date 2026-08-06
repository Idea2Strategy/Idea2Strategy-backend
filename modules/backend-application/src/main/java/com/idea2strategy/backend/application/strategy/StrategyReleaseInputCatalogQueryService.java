package com.idea2strategy.backend.application.strategy;

import java.time.Clock;
import java.util.Objects;

public final class StrategyReleaseInputCatalogQueryService {
    private final StrategyReleaseInputCatalogQueryPort queryPort;
    private final Clock clock;

    public StrategyReleaseInputCatalogQueryService(
            StrategyReleaseInputCatalogQueryPort queryPort,
            Clock clock) {
        this.queryPort = Objects.requireNonNull(queryPort, "queryPort");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public StrategyReleaseInputCatalog getSelectable() {
        return queryPort.findSelectableAt(clock.instant());
    }
}
