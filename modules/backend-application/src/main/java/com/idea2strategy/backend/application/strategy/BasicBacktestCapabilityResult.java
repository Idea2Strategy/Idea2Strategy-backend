package com.idea2strategy.backend.application.strategy;

import com.idea2strategy.backend.application.strategy.BacktestDataCoverage.FeedResolution;
import java.util.List;

public record BasicBacktestCapabilityResult(
        List<FeedResolution> requiredFeeds,
        List<String> requiredFeatures,
        List<BasicBacktestCapabilityIssue> issues) {
    public BasicBacktestCapabilityResult {
        requiredFeeds = List.copyOf(requiredFeeds);
        requiredFeatures = List.copyOf(requiredFeatures);
        issues = List.copyOf(issues);
    }

    public boolean backtestable() {
        return issues.isEmpty();
    }
}
