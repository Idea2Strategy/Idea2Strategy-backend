package com.idea2strategy.backend.application.strategy;

import java.time.Instant;
import java.util.UUID;

public interface DelegatedStrategyAuthorizationPort {
    void requireAuthorized(
            DelegatedStrategyEditor editor,
            UUID strategyId,
            DelegatedStrategyScope scope,
            Instant at);
}
