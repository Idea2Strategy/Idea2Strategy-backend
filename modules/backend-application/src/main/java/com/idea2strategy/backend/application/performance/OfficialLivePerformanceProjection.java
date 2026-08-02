package com.idea2strategy.backend.application.performance;

import com.idea2strategy.backend.domain.competition.CompetitionType;
import com.idea2strategy.backend.domain.performance.BotCurrentPerformance;
import java.util.Objects;

public final class OfficialLivePerformanceProjection {
    private final CompetitionType competitionType;
    private final LivePerformanceSource source;
    private final BotCurrentPerformance performance;

    OfficialLivePerformanceProjection(
            CompetitionType competitionType,
            LivePerformanceSource source,
            BotCurrentPerformance performance) {
        if (competitionType != CompetitionType.LIVE_PAPER || source != LivePerformanceSource.LIVE_TRADING) {
            throw new IllegalArgumentException("only LIVE_PAPER LIVE_TRADING performance can be persisted");
        }
        this.competitionType = competitionType;
        this.source = source;
        this.performance = Objects.requireNonNull(performance, "performance");
    }

    public CompetitionType competitionType() {
        return competitionType;
    }

    public LivePerformanceSource source() {
        return source;
    }

    public BotCurrentPerformance performance() {
        return performance;
    }
}
