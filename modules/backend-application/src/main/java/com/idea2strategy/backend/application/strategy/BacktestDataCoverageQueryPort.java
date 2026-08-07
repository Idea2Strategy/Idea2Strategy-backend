package com.idea2strategy.backend.application.strategy;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Reports which historical feeds and official features a backtest can actually read. */
public interface BacktestDataCoverageQueryPort {
    Set<BacktestDataCoverage.FeedResolution> findAvailableFeeds(Instant observedAt);

    Set<String> findAvailableFeatures(UUID elementCatalogVersionId, Instant observedAt);
}
