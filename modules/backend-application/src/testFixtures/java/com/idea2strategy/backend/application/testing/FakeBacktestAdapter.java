package com.idea2strategy.backend.application.testing;

import com.idea2strategy.backend.application.strategy.BacktestDataCoverage;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class FakeBacktestAdapter {
    private final BacktestDataCoverage coverage;
    private final List<UUID> requestedStrategyIds = new ArrayList<>();

    public FakeBacktestAdapter(BacktestDataCoverage coverage) {
        this.coverage = Objects.requireNonNull(coverage, "coverage");
    }

    public BacktestDataCoverage coverageFor(UUID strategyId) {
        requestedStrategyIds.add(Objects.requireNonNull(strategyId, "strategyId"));
        return coverage;
    }

    public List<UUID> requestedStrategyIds() {
        return List.copyOf(requestedStrategyIds);
    }
}
