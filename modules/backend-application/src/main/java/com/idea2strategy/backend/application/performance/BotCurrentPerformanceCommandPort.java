package com.idea2strategy.backend.application.performance;

public interface BotCurrentPerformanceCommandPort {
    ProjectionWriteDecision save(OfficialLivePerformanceProjection projection);
}
