package com.idea2strategy.backend.application.competition;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

public record OfficialEquityObservation(String normalSegmentId, Instant observedAt, BigDecimal equityAmount) {
    public OfficialEquityObservation {
        Objects.requireNonNull(normalSegmentId, "normalSegmentId");
        if (normalSegmentId.isBlank()) {
            throw new IllegalArgumentException("normalSegmentId must not be blank");
        }
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(equityAmount, "equityAmount");
    }
}
