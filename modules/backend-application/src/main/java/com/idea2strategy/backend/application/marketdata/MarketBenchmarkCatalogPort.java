package com.idea2strategy.backend.application.marketdata;

import com.idea2strategy.backend.domain.strategy.SupportedInstrument;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MarketBenchmarkCatalogPort {
    List<SupportedInstrument> findPublishedBenchmarks();

    default Optional<SupportedInstrument> findById(UUID instrumentId) {
        return findPublishedBenchmarks().stream()
                .filter(instrument -> instrument.id().equals(instrumentId))
                .findFirst();
    }

    static MarketBenchmarkCatalogPort empty() {
        return List::of;
    }
}
