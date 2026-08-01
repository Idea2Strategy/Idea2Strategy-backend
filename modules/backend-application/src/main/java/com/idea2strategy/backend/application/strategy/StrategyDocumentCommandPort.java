package com.idea2strategy.backend.application.strategy;

import com.idea2strategy.backend.domain.strategy.StrategyDocument;

public interface StrategyDocumentCommandPort {
    void save(StrategyDocument document);
}
