package com.idea2strategy.backend.application.testing;

import com.idea2strategy.backend.application.competition.BacktestRoomPort;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class FakeBacktestRoomAdapter implements BacktestRoomPort {
    private final boolean available;
    private final List<UUID> inspectedRooms = new ArrayList<>();

    public FakeBacktestRoomAdapter(boolean available) {
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
