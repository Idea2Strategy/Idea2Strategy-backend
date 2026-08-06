package com.idea2strategy.backend.application.dashboard;

import com.idea2strategy.backend.application.botoperations.BotOperationsStateResolver;
import com.idea2strategy.backend.application.common.CurrentPrincipal;
import java.time.Clock;
import java.util.Objects;

public final class DashboardQueryService {
    private final DashboardQueryPort queryPort;
    private final CurrentPrincipal principal;
    private final Clock clock;

    public DashboardQueryService(DashboardQueryPort queryPort, CurrentPrincipal principal, Clock clock) {
        this.queryPort = Objects.requireNonNull(queryPort, "queryPort");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public DashboardSnapshot getOwnedSnapshot() {
        var now = clock.instant();
        var bots = queryPort.findOwned(principal.accountId()).stream()
                .map(bot -> new DashboardBotView(
                        bot.botId(),
                        bot.name(),
                        BotOperationsStateResolver.resolve(
                                bot.lifecycleStatus(),
                                bot.executionEligibleFrom(),
                                bot.executionBlockedAt(),
                                bot.executionBlockReasonCode(),
                                now),
                        bot.lifecycleChangedAt(),
                        performanceView(bot.performance()),
                        competitionView(bot.competition())))
                .toList();
        return new DashboardSnapshot(now, bots);
    }

    private static DashboardPerformanceView performanceView(DashboardPerformanceProjection performance) {
        if (performance == null) {
            return null;
        }
        return new DashboardPerformanceView(
                performance.equityAmount(),
                performance.totalReturnPct(),
                performance.maxDrawdownPct(),
                performance.sharpeRatio(),
                performance.calculationRulesVersion(),
                performance.updatedAt());
    }

    private static DashboardCompetitionView competitionView(DashboardCompetitionProjection competition) {
        if (competition == null) {
            return null;
        }
        return new DashboardCompetitionView(
                competition.roomId(),
                competition.roomName(),
                competition.roomStatus(),
                competition.participationStatus(),
                competition.evaluationEndsAt(),
                competition.timezoneName());
    }
}
