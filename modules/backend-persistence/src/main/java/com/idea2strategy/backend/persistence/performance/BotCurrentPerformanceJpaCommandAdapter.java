package com.idea2strategy.backend.persistence.performance;

import com.idea2strategy.backend.application.performance.BotCurrentPerformanceCommandPort;
import com.idea2strategy.backend.domain.performance.BotCurrentPerformance;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class BotCurrentPerformanceJpaCommandAdapter implements BotCurrentPerformanceCommandPort {
    private final BotCurrentPerformanceSpringDataRepository repository;

    public BotCurrentPerformanceJpaCommandAdapter(BotCurrentPerformanceSpringDataRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void save(BotCurrentPerformance performance) {
        repository.saveAndFlush(BotCurrentPerformanceJpaEntity.from(performance));
    }
}
