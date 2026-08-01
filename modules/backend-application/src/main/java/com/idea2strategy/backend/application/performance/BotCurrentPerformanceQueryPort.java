package com.idea2strategy.backend.application.performance;

import com.idea2strategy.backend.domain.performance.BotCurrentPerformance;
import java.util.Optional;
import java.util.UUID;

public interface BotCurrentPerformanceQueryPort {
    Optional<BotCurrentPerformance> findByBotId(UUID botId);
}
