package com.idea2strategy.backend.application.competition;

import java.util.Objects;
import java.util.UUID;

public final class CompetitionDependencyService {
    private final BotReferencePort botReferencePort;
    private final TradingRoomPort tradingRoomPort;
    private final BacktestRoomPort backtestRoomPort;

    public CompetitionDependencyService(
            BotReferencePort botReferencePort,
            TradingRoomPort tradingRoomPort,
            BacktestRoomPort backtestRoomPort) {
        this.botReferencePort = Objects.requireNonNull(botReferencePort, "botReferencePort");
        this.tradingRoomPort = Objects.requireNonNull(tradingRoomPort, "tradingRoomPort");
        this.backtestRoomPort = Objects.requireNonNull(backtestRoomPort, "backtestRoomPort");
    }

    public CompetitionDependencyReadiness inspect(UUID roomId, UUID botId) {
        Objects.requireNonNull(roomId, "roomId");
        Objects.requireNonNull(botId, "botId");
        return new CompetitionDependencyReadiness(
                botReferencePort.exists(botId),
                tradingRoomPort.isAvailable(roomId),
                backtestRoomPort.isAvailable(roomId));
    }
}
