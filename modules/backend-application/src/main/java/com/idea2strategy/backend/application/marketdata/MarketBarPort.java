package com.idea2strategy.backend.application.marketdata;

import java.util.List;
import java.util.UUID;

public interface MarketBarPort {
    List<MarketBar> findRecent(UUID instrumentId, MarketBarTimeframe timeframe, int limit);
}
