package com.idea2strategy.backend.domain.strategy;

import java.time.Instant;
import java.util.UUID;

public record StrategyCreated(UUID strategyId, UUID ownerAccountId, StrategyMode mode, Instant occurredAt) {}
