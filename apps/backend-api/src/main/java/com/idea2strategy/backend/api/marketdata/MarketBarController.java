package com.idea2strategy.backend.api.marketdata;

import com.idea2strategy.backend.application.marketdata.MarketBarService;
import com.idea2strategy.backend.application.marketdata.MarketBarSnapshot;
import com.idea2strategy.backend.application.marketdata.MarketBarView;
import com.idea2strategy.backend.application.marketdata.MarketBarTimeframe;
import com.idea2strategy.backend.application.marketdata.MarketBarWindow;
import com.idea2strategy.backend.application.marketdata.MarketBarWindowSnapshot;
import com.idea2strategy.backend.application.marketdata.MarketBarCoverageStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/market-data/instruments/{instrumentId}/bars")
@ConditionalOnBean(MarketBarService.class)
public class MarketBarController {
    private final MarketBarService service;

    public MarketBarController(MarketBarService service) {
        this.service = service;
    }

    @GetMapping
    public SnapshotResponse recent(
            @PathVariable UUID instrumentId,
            @RequestParam(defaultValue = "30m") String timeframe,
            @RequestParam(defaultValue = "400") int limit,
            @RequestParam(required = false) String window) {
        MarketBarTimeframe parsedTimeframe = MarketBarTimeframe.parse(timeframe);
        if (window != null) {
            MarketBarWindowSnapshot snapshot = service.findWindowSnapshot(
                    instrumentId, parsedTimeframe, MarketBarWindow.parse(window));
            return SnapshotResponse.window(snapshot, parsedTimeframe);
        }
        MarketBarSnapshot snapshot = service.findRecentSnapshot(instrumentId, parsedTimeframe, limit);
        return new SnapshotResponse(
                snapshot.instrumentId(), snapshot.symbol(), parsedTimeframe.value(),
                null, null, null, null, null, null, null,
                snapshot.bars().stream().map(BarResponse::from).toList());
    }

    public record SnapshotResponse(
            UUID instrumentId,
            String symbol,
            String timeframe,
            String window,
            Instant requestedFrom,
            Instant requestedTo,
            Instant availableFrom,
            Instant availableTo,
            MarketBarCoverageStatus coverageStatus,
            String reasonCode,
            List<BarResponse> bars) {
        static SnapshotResponse window(
                MarketBarWindowSnapshot snapshot, MarketBarTimeframe timeframe) {
            return new SnapshotResponse(
                    snapshot.instrumentId(), snapshot.symbol(), timeframe.value(),
                    snapshot.window().value(), snapshot.requestedFrom(), snapshot.requestedTo(),
                    snapshot.availableFrom(), snapshot.availableTo(), snapshot.coverageStatus(),
                    snapshot.reasonCode(), snapshot.bars().stream().map(BarResponse::from).toList());
        }
    }

    public record BarResponse(
            String eventId,
            Instant occurredAt,
            long sequence,
            int revision,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal close,
            BigDecimal volume,
            String provider,
            String feed) {
        static BarResponse from(MarketBarView bar) {
            return new BarResponse(
                    bar.eventId(), bar.occurredAt(), bar.sequence(), bar.revision(),
                    bar.open(), bar.high(), bar.low(), bar.close(), bar.volume(),
                    bar.provider(), bar.feed());
        }
    }
}
