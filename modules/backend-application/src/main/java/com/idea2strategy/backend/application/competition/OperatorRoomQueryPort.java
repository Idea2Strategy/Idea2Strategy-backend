package com.idea2strategy.backend.application.competition;

import java.util.Optional;
import java.util.UUID;

public interface OperatorRoomQueryPort {
    Optional<OperatorRoomView> findOfficialRoom(UUID roomId);
}
