package com.idea2strategy.backend.persistence.competition;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface CompetitionRoomRulesSpringDataRepository extends JpaRepository<CompetitionRoomRulesJpaEntity, UUID> {}
