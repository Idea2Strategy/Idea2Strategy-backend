package com.idea2strategy.backend.application.strategy;

import com.idea2strategy.backend.domain.strategy.StrategyDocument;
import java.time.Instant;

public interface DelegatedBasicEditCommandPort {
    DelegatedBasicEditReplaceResult replace(
            StrategyDocument document,
            long expectedEditSequence,
            DelegatedStrategyEditor editor,
            Instant at);
}
