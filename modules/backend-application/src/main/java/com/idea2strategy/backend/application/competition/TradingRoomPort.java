package com.idea2strategy.backend.application.competition;

import java.util.UUID;

public interface TradingRoomPort {
    boolean isAvailable(UUID roomId);
}
