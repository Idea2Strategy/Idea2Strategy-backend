package com.idea2strategy.backend.persistence.competition;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompetitionLiveRoomRulesSpringDataRepository
        extends JpaRepository<CompetitionLiveRoomRulesJpaEntity, UUID> {}
