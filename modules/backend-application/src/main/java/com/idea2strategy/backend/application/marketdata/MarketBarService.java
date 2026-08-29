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
    private final MarketBenchmarkCatalogPort benchmarkCatalog;
    private final CurrentPrincipal principal;

    public MarketBarService(
            MarketBarPort port,
            BasicStrategyCatalogQueryService catalog,
            CurrentPrincipal principal) {
        this(port, catalog, MarketBenchmarkCatalogPort.empty(), principal);
    }

    public MarketBarService(
            MarketBarPort port,
            BasicStrategyCatalogQueryService catalog,
            MarketBenchmarkCatalogPort benchmarkCatalog,
            CurrentPrincipal principal) {
        this.port = Objects.requireNonNull(port, "port");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.benchmarkCatalog = Objects.requireNonNull(benchmarkCatalog, "benchmarkCatalog");
        this.principal = Objects.requireNonNull(principal, "principal");
    }

    public List<SupportedInstrument> findBenchmarks() {
        principal.accountId();
        return benchmarkCatalog.findPublishedBenchmarks();
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
        if (limit < 1 || limit > 5000) {
            throw new IllegalArgumentException("limit must be between 1 and 5000");
        }
        List<MarketBarView> bars = port.findRecent(instrumentId, timeframe, limit).stream()
                .map(bar -> view(instrument.symbol(), bar))
                .toList();
        return new MarketBarSnapshot(instrumentId, instrument.symbol(), bars);
    }

    public MarketBarWindowSnapshot findWindowSnapshot(
            UUID instrumentId, MarketBarTimeframe timeframe, MarketBarWindow window) {
        SupportedInstrument instrument = authorizeAndRequireSupported(instrumentId);
        Objects.requireNonNull(timeframe, "timeframe");
        Objects.requireNonNull(window, "window");
        List<MarketBar> stored = port.findRecent(instrumentId, timeframe, 1000);
        if (stored.isEmpty()) {
            return new MarketBarWindowSnapshot(
                    instrumentId, instrument.symbol(), window,
                    null, null, null, null,
                    MarketBarCoverageStatus.EMPTY,
                    "NO_DATA_FOR_INSTRUMENT_TIMEFRAME",
                    List.of());
        }
        MarketBar first = stored.get(0);
        MarketBar latest = stored.get(stored.size() - 1);
        var requestedFrom = window.startAt(latest.occurredAt());
        List<MarketBarView> bars = stored.stream()
                .filter(bar -> !bar.occurredAt().isBefore(requestedFrom))
                .map(bar -> view(instrument.symbol(), bar))
                .toList();
        boolean complete = !first.occurredAt().isAfter(requestedFrom);
        return new MarketBarWindowSnapshot(
                instrumentId, instrument.symbol(), window,
                requestedFrom, latest.occurredAt(), bars.get(0).occurredAt(), latest.occurredAt(),
                complete ? MarketBarCoverageStatus.COMPLETE : MarketBarCoverageStatus.PARTIAL,
                complete ? null : "HISTORY_STARTS_AFTER_REQUESTED_WINDOW",
                bars);
    }

    private SupportedInstrument authorizeAndRequireSupported(UUID instrumentId) {
        Objects.requireNonNull(instrumentId, "instrumentId");
        principal.accountId();
        return catalog.getSupportedInstruments().stream()
                .filter(instrument -> instrument.id().equals(instrumentId))
                .findFirst()
                .or(() -> benchmarkCatalog.findById(instrumentId))
                .orElseThrow(() -> new UnsupportedMarketInstrumentException(instrumentId));
    }

    private static MarketBarView view(String symbol, MarketBar bar) {
        return new MarketBarView(
                bar.eventId(), bar.instrumentId(), symbol, bar.provider(), bar.feed(),
                bar.occurredAt(), bar.sequence(), bar.revision(), bar.open(), bar.high(),
                bar.low(), bar.close(), bar.volume());
    }
}
