package com.idea2strategy.backend.persistence.competition;

import com.idea2strategy.backend.application.competition.CompetitionRoomCommandPort;
import com.idea2strategy.backend.domain.competition.CompetitionRoom;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class CompetitionRoomJpaCommandAdapter implements CompetitionRoomCommandPort {
    private final CompetitionRoomSpringDataRepository roomRepository;
    private final CompetitionRoomRulesSpringDataRepository rulesRepository;
    private final CompetitionRoomScheduleSpringDataRepository scheduleRepository;

    public CompetitionRoomJpaCommandAdapter(
            CompetitionRoomSpringDataRepository roomRepository,
            CompetitionRoomRulesSpringDataRepository rulesRepository,
            CompetitionRoomScheduleSpringDataRepository scheduleRepository) {
        this.roomRepository = roomRepository;
        this.rulesRepository = rulesRepository;
        this.scheduleRepository = scheduleRepository;
    }

    @Override
    @Transactional
    public void save(CompetitionRoom room) {
        roomRepository.saveAndFlush(CompetitionRoomJpaEntity.from(room));
        rulesRepository.save(CompetitionRoomRulesJpaEntity.from(room));
        scheduleRepository.save(CompetitionRoomScheduleJpaEntity.from(room));
        scheduleRepository.flush();
    }
}
