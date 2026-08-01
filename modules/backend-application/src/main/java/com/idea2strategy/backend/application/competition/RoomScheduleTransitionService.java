package com.idea2strategy.backend.application.competition;

import java.time.Clock;
import java.util.Objects;

public final class RoomScheduleTransitionService {
    private static final int MAX_BATCH_SIZE = 1_000;
    private final RoomScheduleTransitionPort transitionPort;
    private final Clock clock;

    public RoomScheduleTransitionService(RoomScheduleTransitionPort transitionPort, Clock clock) {
        this.transitionPort = Objects.requireNonNull(transitionPort, "transitionPort");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public RoomScheduleTransitionReport run(int limit) {
        if (limit < 1 || limit > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and 1000");
        }
        return transitionPort.advanceDue(clock.instant(), limit);
    }
}
