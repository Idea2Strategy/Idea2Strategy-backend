package com.idea2strategy.backend.application.dashboard;

import com.idea2strategy.backend.application.botoperations.BotOperationsState;
import java.time.Instant;
import java.util.UUID;

public record DashboardBotView(
        UUID botId,
        String name,
        BotOperationsState state,
        Instant lifecycleChangedAt,
        DashboardPerformanceView performance,
        DashboardCompetitionView competition) {}
