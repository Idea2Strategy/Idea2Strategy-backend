package com.idea2strategy.backend.application.competition;

import java.util.UUID;

public interface BacktestRoomPort {
    boolean isAvailable(UUID roomId);
}
