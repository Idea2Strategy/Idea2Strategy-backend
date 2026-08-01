package com.idea2strategy.backend.application.botoperations;

import java.time.Instant;
import java.util.UUID;

public record BotOperationsView(
        UUID botId,
        String name,
        BotOperationsState state,
        Instant lifecycleChangedAt,
        Instant executionBlockedAt,
        String executionBlockReasonCode,
        long lastEventSequence) {}
