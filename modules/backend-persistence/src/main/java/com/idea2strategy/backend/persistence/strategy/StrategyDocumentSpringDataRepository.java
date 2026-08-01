package com.idea2strategy.backend.persistence.strategy;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StrategyDocumentSpringDataRepository
        extends JpaRepository<StrategyDocumentJpaEntity, UUID> {}
