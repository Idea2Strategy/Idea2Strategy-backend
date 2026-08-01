package com.idea2strategy.backend.application.strategy;

import com.idea2strategy.backend.domain.strategy.Strategy;
import com.idea2strategy.backend.domain.strategy.StrategyDocument;

public interface BasicStrategyDraftCommandPort {
    void create(Strategy strategy, StrategyDocument document);

    boolean replaceDocument(StrategyDocument document, long expectedEditSequence);
}
