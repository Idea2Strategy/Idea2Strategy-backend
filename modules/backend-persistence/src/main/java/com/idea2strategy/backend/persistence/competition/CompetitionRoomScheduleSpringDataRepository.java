package com.idea2strategy.backend.persistence.competition;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface CompetitionRoomScheduleSpringDataRepository extends JpaRepository<CompetitionRoomScheduleJpaEntity, UUID> {}
