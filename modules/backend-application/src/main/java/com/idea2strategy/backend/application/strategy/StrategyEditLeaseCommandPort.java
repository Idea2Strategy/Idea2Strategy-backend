package com.idea2strategy.backend.application.strategy;

import com.idea2strategy.backend.domain.strategy.StrategyEditLease;
import java.time.Instant;
import java.util.UUID;

public interface StrategyEditLeaseCommandPort {
    boolean acquire(StrategyEditLease lease, Instant now);

    boolean heartbeat(
            UUID strategyId,
            UUID sessionId,
            String tokenDigest,
            Instant heartbeatAt,
            Instant expiresAt);

    boolean release(UUID strategyId, UUID sessionId, String tokenDigest);
}
