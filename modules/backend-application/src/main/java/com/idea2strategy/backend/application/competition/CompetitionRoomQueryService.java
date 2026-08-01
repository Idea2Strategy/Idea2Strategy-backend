package com.idea2strategy.backend.application.competition;

import com.idea2strategy.backend.domain.competition.CompetitionRoom;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

public final class CompetitionRoomQueryService {
    private final CompetitionRoomQueryPort queryPort;

    public CompetitionRoomQueryService(CompetitionRoomQueryPort queryPort) {
        this.queryPort = Objects.requireNonNull(queryPort, "queryPort");
    }

    public CompetitionRoom get(UUID roomId) {
        return queryPort.findById(roomId)
                .orElseThrow(() -> new NoSuchElementException("Room not found: " + roomId));
    }
}
