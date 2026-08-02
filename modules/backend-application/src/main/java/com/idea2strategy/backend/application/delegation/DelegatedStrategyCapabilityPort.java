package com.idea2strategy.backend.application.delegation;

import com.idea2strategy.backend.application.strategy.DelegatedStrategyEditor;
import com.idea2strategy.backend.application.strategy.DelegatedStrategyScope;
import java.time.Instant;

@FunctionalInterface
public interface DelegatedStrategyCapabilityPort {
    void requireAuthorized(
            DelegatedStrategyEditor editor,
            DelegatedStrategyScope scope,
            long expectedAuthorizationVersion,
            Instant at);
}
