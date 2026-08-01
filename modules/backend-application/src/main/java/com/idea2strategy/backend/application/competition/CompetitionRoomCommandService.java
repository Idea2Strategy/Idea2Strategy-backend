package com.idea2strategy.backend.application.competition;

import com.idea2strategy.backend.domain.competition.CompetitionRoom;
import java.util.Objects;

public final class CompetitionRoomCommandService {
    private final CompetitionRoomCommandPort commandPort;

    public CompetitionRoomCommandService(CompetitionRoomCommandPort commandPort) {
        this.commandPort = Objects.requireNonNull(commandPort, "commandPort");
    }

    public void save(CompetitionRoom room) {
        commandPort.save(Objects.requireNonNull(room, "room"));
    }
}
