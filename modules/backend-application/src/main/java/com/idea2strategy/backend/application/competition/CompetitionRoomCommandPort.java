package com.idea2strategy.backend.application.competition;

import com.idea2strategy.backend.domain.competition.CompetitionRoom;

public interface CompetitionRoomCommandPort {
    void save(CompetitionRoom room);
}
