package com.idea2strategy.backend.application.strategy;

import com.idea2strategy.backend.domain.strategy.Strategy;
import com.idea2strategy.backend.domain.strategy.StrategyDocument;
import java.time.Instant;
import java.util.UUID;

public interface BasicStrategyDraftCommandPort {
    void create(Strategy strategy, StrategyDocument document);

    StrategyDraftReplaceResult replaceDocument(
            StrategyDocument document,
            long expectedEditSequence,
            UUID sessionId,
            String leaseTokenDigest,
            Instant now);
}
