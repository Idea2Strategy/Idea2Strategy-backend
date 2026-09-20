package com.idea2strategy.backend.application.marketdata;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MarketBarWindowSnapshot(
        UUID instrumentId,
        String symbol,
        MarketBarWindow window,
        Instant requestedFrom,
        Instant requestedTo,
        Instant availableFrom,
        Instant availableTo,
        MarketBarCoverageStatus coverageStatus,
        String reasonCode,
        List<MarketBarView> bars) {
    public MarketBarWindowSnapshot {
        bars = List.copyOf(bars);
    }
}
