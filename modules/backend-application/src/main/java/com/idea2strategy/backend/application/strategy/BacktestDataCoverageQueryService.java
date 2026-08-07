package com.idea2strategy.backend.application.strategy;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * Resolves the backtest data coverage that a validation run is judged against.
 *
 * <p>The coverage echoes the catalog's own data requirement version, because a mismatch collapses
 * every block finding into a single version error and hides the real feed and feature results.
 */
public class BacktestDataCoverageQueryService {
    private final BacktestDataCoverageQueryPort port;
    private final Clock clock;

    public BacktestDataCoverageQueryService(BacktestDataCoverageQueryPort port, Clock clock) {
        this.port = Objects.requireNonNull(port, "port");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public BacktestDataCoverage coverageFor(BasicStrategyCatalog catalog) {
        Objects.requireNonNull(catalog, "catalog");
        Instant observedAt = clock.instant();
        return new BacktestDataCoverage(
                catalog.version().dataRequirementVersion(),
                port.findAvailableFeeds(observedAt),
                port.findAvailableFeatures(catalog.version().id(), observedAt));
    }
}
