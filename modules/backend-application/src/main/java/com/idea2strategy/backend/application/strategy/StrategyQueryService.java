package com.idea2strategy.backend.application.strategy;

import com.idea2strategy.backend.application.common.CurrentPrincipal;
import com.idea2strategy.backend.domain.strategy.Strategy;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

public final class StrategyQueryService {
    private final StrategyQueryPort queryPort;
    private final CurrentPrincipal principal;

    public StrategyQueryService(StrategyQueryPort queryPort, CurrentPrincipal principal) {
        this.queryPort = Objects.requireNonNull(queryPort, "queryPort");
        this.principal = Objects.requireNonNull(principal, "principal");
    }

    public Strategy getOwned(UUID strategyId) {
        return queryPort.findOwnedById(strategyId, principal.accountId())
                .orElseThrow(() -> new NoSuchElementException("Strategy not found"));
    }
}
