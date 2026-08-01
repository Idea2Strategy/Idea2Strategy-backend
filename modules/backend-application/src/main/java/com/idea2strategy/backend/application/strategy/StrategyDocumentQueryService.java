package com.idea2strategy.backend.application.strategy;

import com.idea2strategy.backend.application.common.CurrentPrincipal;
import com.idea2strategy.backend.domain.strategy.StrategyDocument;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

public final class StrategyDocumentQueryService {
    private final StrategyDocumentQueryPort queryPort;
    private final CurrentPrincipal principal;

    public StrategyDocumentQueryService(StrategyDocumentQueryPort queryPort, CurrentPrincipal principal) {
        this.queryPort = Objects.requireNonNull(queryPort, "queryPort");
        this.principal = Objects.requireNonNull(principal, "principal");
    }

    public StrategyDocument getOwned(UUID strategyId) {
        return queryPort.findOwnedByStrategyId(strategyId, principal.accountId())
                .orElseThrow(() -> new NoSuchElementException("Strategy document not found"));
    }
}
