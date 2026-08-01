package com.idea2strategy.backend.persistence.strategy;

import com.idea2strategy.backend.application.strategy.StrategyCommandPort;
import com.idea2strategy.backend.domain.strategy.Strategy;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class StrategyJpaCommandAdapter implements StrategyCommandPort {
    private final StrategySpringDataRepository repository;

    public StrategyJpaCommandAdapter(StrategySpringDataRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void save(Strategy strategy) {
        repository.saveAndFlush(StrategyJpaEntity.from(strategy));
    }
}
