package com.idea2strategy.backend.domain.competition;

import java.math.BigDecimal;
import java.util.Objects;

public record ScoringComponent(
        ScoringMetric metric, ScoringDirection direction, BigDecimal coefficient) {
    public ScoringComponent {
        Objects.requireNonNull(metric, "metric");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(coefficient, "coefficient");
        if (direction != metric.direction()) {
            throw new IllegalArgumentException("direction does not match official metric direction: " + metric);
        }
        if (coefficient.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("coefficient must be positive");
        }
    }
}
