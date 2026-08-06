package com.idea2strategy.backend.application.marketdata;

import java.util.List;
import java.util.UUID;

public record MarketBarSnapshot(UUID instrumentId, String symbol, List<MarketBarView> bars) {
    public MarketBarSnapshot {
        bars = List.copyOf(bars);
    }
}
