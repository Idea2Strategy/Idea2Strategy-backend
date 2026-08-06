package com.idea2strategy.backend.api.marketdata;

import com.idea2strategy.backend.application.marketdata.MarketBarService;
import com.idea2strategy.backend.application.marketdata.MarketBarSnapshot;
import com.idea2strategy.backend.application.marketdata.MarketBarView;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/market-data/instruments/{instrumentId}/bars")
@ConditionalOnBean(MarketBarService.class)
public class MarketBarController {
    private final MarketBarService service;
    private final MarketBarSseHub sseHub;

    public MarketBarController(MarketBarService service, MarketBarSseHub sseHub) {
        this.service = service;
        this.sseHub = sseHub;
    }

    @GetMapping
    public SnapshotResponse recent(
            @PathVariable UUID instrumentId,
            @RequestParam(defaultValue = "300") int limit) {
        MarketBarSnapshot snapshot = service.findRecentSnapshot(instrumentId, limit);
        return new SnapshotResponse(
                snapshot.instrumentId(), snapshot.symbol(), "1m",
                snapshot.bars().stream().map(BarResponse::from).toList());
    }

    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable UUID instrumentId) {
        return sseHub.open(instrumentId);
    }

    public record SnapshotResponse(
            UUID instrumentId, String symbol, String timeframe, List<BarResponse> bars) {}

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
