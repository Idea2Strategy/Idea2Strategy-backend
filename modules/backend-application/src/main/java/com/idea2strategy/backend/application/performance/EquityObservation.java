package com.idea2strategy.backend.application.performance;

import java.math.BigDecimal;
import java.util.Objects;

public record EquityObservation(long sourceEventSequence, BigDecimal equityAmount) {
    public EquityObservation {
        if (sourceEventSequence < 0) {
            throw new IllegalArgumentException("sourceEventSequence must not be negative");
        }
        Objects.requireNonNull(equityAmount, "equityAmount");
    }
}
