package com.idea2strategy.backend.persistence.performance;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface BotCurrentPerformanceSpringDataRepository extends JpaRepository<BotCurrentPerformanceJpaEntity, UUID> {}
