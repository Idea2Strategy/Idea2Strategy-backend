package com.idea2strategy.backend.application.competition;

import com.idea2strategy.backend.domain.competition.CompetitionRoom;
import java.util.Optional;
import java.util.UUID;

public interface CompetitionRoomQueryPort {
    Optional<CompetitionRoom> findById(UUID roomId);
}
