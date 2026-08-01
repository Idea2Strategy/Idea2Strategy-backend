package com.idea2strategy.backend.application.testing;

import com.idea2strategy.backend.application.competition.TradingRoomPort;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class FakeTradingRoomAdapter implements TradingRoomPort {
    private final boolean available;
    private final List<UUID> inspectedRooms = new ArrayList<>();

    public FakeTradingRoomAdapter(boolean available) {
        this.available = available;
    }

    @Override
    public boolean isAvailable(UUID roomId) {
        inspectedRooms.add(roomId);
        return available;
    }

    public List<UUID> inspectedRooms() {
        return List.copyOf(inspectedRooms);
    }
}
