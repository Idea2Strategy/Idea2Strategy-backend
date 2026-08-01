package com.idea2strategy.backend.application.performance;

import com.idea2strategy.backend.domain.performance.BotCurrentPerformance;

public interface BotCurrentPerformanceCommandPort {
    void save(BotCurrentPerformance performance);
}
