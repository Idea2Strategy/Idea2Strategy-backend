package com.idea2strategy.backend.application.performance;

import com.idea2strategy.backend.domain.performance.BotCurrentPerformance;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

public final class BotCurrentPerformanceQueryService {
    private final BotCurrentPerformanceQueryPort queryPort;

    public BotCurrentPerformanceQueryService(BotCurrentPerformanceQueryPort queryPort) {
        this.queryPort = Objects.requireNonNull(queryPort, "queryPort");
    }

    public BotCurrentPerformance get(UUID botId) {
        return queryPort.findByBotId(botId)
                .orElseThrow(() -> new NoSuchElementException("Performance not found for bot: " + botId));
    }
}
