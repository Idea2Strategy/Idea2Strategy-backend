package com.idea2strategy.backend.persistence.strategy;

import com.idea2strategy.backend.application.strategy.StrategyValidationRunCommandPort;
import com.idea2strategy.backend.domain.strategy.StrategyValidationRun;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class StrategyValidationRunJpaCommandAdapter implements StrategyValidationRunCommandPort {
    private final StrategyValidationRunSpringDataRepository repository;

    public StrategyValidationRunJpaCommandAdapter(StrategyValidationRunSpringDataRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void save(StrategyValidationRun run) {
        repository.saveAndFlush(StrategyValidationRunJpaEntity.from(run));
    }
}
