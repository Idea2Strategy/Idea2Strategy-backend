package com.idea2strategy.backend.application.botoperations;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BotOperationsQueryPort {
    List<BotOperationsProjection> findOwnedBots(UUID ownerAccountId);

    Optional<BotJudgmentLogSlice> findOwnedJudgments(
            UUID botId, UUID ownerAccountId, long afterSequence, int limit);
}
