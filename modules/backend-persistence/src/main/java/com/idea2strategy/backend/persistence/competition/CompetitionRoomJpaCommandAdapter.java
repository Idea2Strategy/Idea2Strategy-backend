package com.idea2strategy.backend.persistence.competition;

import com.idea2strategy.backend.application.competition.CompetitionRoomCommandPort;
import com.idea2strategy.backend.domain.competition.CompetitionRoom;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class CompetitionRoomJpaCommandAdapter implements CompetitionRoomCommandPort {
    private final EntityManager entityManager;

    public CompetitionRoomJpaCommandAdapter(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    @Transactional
    public void save(CompetitionRoom room) {
        // This port creates a new aggregate only. persist() makes duplicate ids fail instead of
        // silently merging a stale aggregate over the guarded configuration-update path.
        entityManager.persist(CompetitionRoomJpaEntity.from(room));
        entityManager.persist(CompetitionRoomRulesJpaEntity.from(room));
        if (room.liveRules() != null) {
            entityManager.persist(CompetitionLiveRoomRulesJpaEntity.from(room));
        }
        entityManager.persist(CompetitionRoomScheduleJpaEntity.from(room));
        entityManager.flush();
    }
}
