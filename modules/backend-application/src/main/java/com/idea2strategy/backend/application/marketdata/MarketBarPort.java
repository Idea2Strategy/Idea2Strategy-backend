package com.idea2strategy.backend.application.marketdata;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

public interface MarketBarPort {
    List<MarketBar> findRecent(UUID instrumentId, int limit);

    AutoCloseable subscribe(UUID instrumentId, Consumer<MarketBar> listener);
}
