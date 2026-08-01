package com.idea2strategy.backend.persistence.strategy;

import com.idea2strategy.backend.application.strategy.StrategyDocumentCommandPort;
import com.idea2strategy.backend.domain.strategy.StrategyDocument;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class StrategyDocumentJpaCommandAdapter implements StrategyDocumentCommandPort {
    private final StrategyDocumentSpringDataRepository repository;

    public StrategyDocumentJpaCommandAdapter(StrategyDocumentSpringDataRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void save(StrategyDocument document) {
        repository.saveAndFlush(StrategyDocumentJpaEntity.from(document));
    }
}
