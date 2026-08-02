package com.idea2strategy.backend.application.accountclosure;

import java.time.Instant;
import java.util.UUID;

public interface AccountClosureReadinessProbe {
    ClosureDomain domain();

    ClosureReadiness evaluate(UUID accountId, UUID correlationId, Instant observedAt);
}
