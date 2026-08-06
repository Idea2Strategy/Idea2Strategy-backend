package com.idea2strategy.backend.application.dashboard;

import com.idea2strategy.backend.domain.botcontrol.BotLifecycleStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record DashboardBotProjection(
        UUID botId,
        String name,
        BotLifecycleStatus lifecycleStatus,
        Instant lifecycleChangedAt,
        Instant executionEligibleFrom,
        Instant executionBlockedAt,
        String executionBlockReasonCode,
        DashboardPerformanceProjection performance,
        DashboardCompetitionProjection competition) {
    public DashboardBotProjection {
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(lifecycleStatus, "lifecycleStatus");
        Objects.requireNonNull(lifecycleChangedAt, "lifecycleChangedAt");
        Objects.requireNonNull(executionEligibleFrom, "executionEligibleFrom");
    }
}
