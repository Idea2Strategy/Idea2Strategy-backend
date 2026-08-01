package com.idea2strategy.backend.application.strategy;

import java.util.Objects;
import java.util.Set;

public record BacktestDataCoverage(
        String dataRequirementVersion,
        Set<FeedResolution> feeds,
        Set<String> features) {
    public BacktestDataCoverage {
        dataRequirementVersion = requireText(dataRequirementVersion, "dataRequirementVersion");
        feeds = Set.copyOf(feeds);
        features = Set.copyOf(features);
        features.forEach(feature -> requireText(feature, "feature"));
    }

    public record FeedResolution(String feed, String resolution) {
        public FeedResolution {
            feed = requireText(feed, "feed");
            resolution = requireText(resolution, "resolution");
        }
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
