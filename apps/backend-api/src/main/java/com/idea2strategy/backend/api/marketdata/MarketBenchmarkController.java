package com.idea2strategy.backend.api.marketdata;

import com.idea2strategy.backend.application.marketdata.MarketBarService;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/market-data/benchmarks")
@ConditionalOnBean(MarketBarService.class)
public class MarketBenchmarkController {
    private final MarketBarService service;

    public MarketBenchmarkController(MarketBarService service) {
        this.service = service;
    }

    @GetMapping
    public BenchmarkResponse benchmarks() {
        return new BenchmarkResponse(service.findBenchmarks().stream()
                .map(instrument -> new BenchmarkInstrumentResponse(
                        instrument.id(), instrument.symbol(), displayName(instrument.symbol())))
                .toList());
    }

    private static String displayName(String symbol) {
        return switch (symbol) {
            case "SPX" -> "S&P 500";
            case "NDX" -> "NASDAQ-100";
            default -> symbol;
        };
    }

    public record BenchmarkResponse(List<BenchmarkInstrumentResponse> instruments) {}

    public record BenchmarkInstrumentResponse(UUID instrumentId, String symbol, String name) {}
}
