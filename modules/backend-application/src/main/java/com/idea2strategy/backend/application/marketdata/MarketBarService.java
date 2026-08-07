package com.idea2strategy.backend.application.marketdata;

import com.idea2strategy.backend.application.common.CurrentPrincipal;
import com.idea2strategy.backend.application.strategy.BasicStrategyCatalogQueryService;
import com.idea2strategy.backend.domain.strategy.SupportedInstrument;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class MarketBarService {
    private final MarketBarPort port;
    private final BasicStrategyCatalogQueryService catalog;
    private final CurrentPrincipal principal;

    public MarketBarService(
            MarketBarPort port,
            BasicStrategyCatalogQueryService catalog,
            CurrentPrincipal principal) {
        this.port = Objects.requireNonNull(port, "port");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.principal = Objects.requireNonNull(principal, "principal");
    }

    public List<MarketBarView> findRecent(UUID instrumentId, int limit) {
        return findRecentSnapshot(instrumentId, MarketBarTimeframe.THIRTY_MINUTES, limit).bars();
    }

    public MarketBarSnapshot findRecentSnapshot(UUID instrumentId, int limit) {
        return findRecentSnapshot(instrumentId, MarketBarTimeframe.THIRTY_MINUTES, limit);
    }

    public MarketBarSnapshot findRecentSnapshot(
            UUID instrumentId, MarketBarTimeframe timeframe, int limit) {
        SupportedInstrument instrument = authorizeAndRequireSupported(instrumentId);
        Objects.requireNonNull(timeframe, "timeframe");
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000");
        }
        List<MarketBarView> bars = port.findRecent(instrumentId, timeframe, limit).stream()
                .map(bar -> view(instrument.symbol(), bar))
                .toList();
        return new MarketBarSnapshot(instrumentId, instrument.symbol(), bars);
    }

    private SupportedInstrument authorizeAndRequireSupported(UUID instrumentId) {
        Objects.requireNonNull(instrumentId, "instrumentId");
        principal.accountId();
        return catalog.getSupportedInstruments().stream()
                .filter(instrument -> instrument.id().equals(instrumentId))
                .findFirst()
                .orElseThrow(() -> new UnsupportedMarketInstrumentException(instrumentId));
    }

    private static MarketBarView view(String symbol, MarketBar bar) {
        return new MarketBarView(
                bar.eventId(), bar.instrumentId(), symbol, bar.provider(), bar.feed(),
                bar.occurredAt(), bar.sequence(), bar.revision(), bar.open(), bar.high(),
                bar.low(), bar.close(), bar.volume());
    }
}
