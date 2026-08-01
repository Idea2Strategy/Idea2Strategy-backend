package com.idea2strategy.backend.application.strategy;

import com.idea2strategy.backend.domain.strategy.Strategy;

public interface StrategyCommandPort {
    void save(Strategy strategy);
}
