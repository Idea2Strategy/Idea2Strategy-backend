package com.idea2strategy.backend.application.strategy;

import com.idea2strategy.backend.application.common.CurrentPrincipal;
import java.time.Clock;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

public final class StrategyDeletionCommandService {
    private final StrategyDeletionCommandPort commandPort;
    private final CurrentPrincipal principal;
    private final Clock clock;

    public StrategyDeletionCommandService(
            StrategyDeletionCommandPort commandPort, CurrentPrincipal principal, Clock clock) {
        this.commandPort = Objects.requireNonNull(commandPort, "commandPort");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void delete(UUID strategyId) {
        Objects.requireNonNull(strategyId, "strategyId");
        StrategyDeletionResult result = commandPort.deleteOwned(
                strategyId, principal.accountId(), clock.instant());
        if (result == StrategyDeletionResult.NOT_FOUND) {
            throw new NoSuchElementException("Strategy not found");
        }
    }
}
