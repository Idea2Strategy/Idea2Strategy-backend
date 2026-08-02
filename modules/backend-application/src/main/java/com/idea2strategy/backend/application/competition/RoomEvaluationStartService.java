package com.idea2strategy.backend.application.competition;

import java.time.Clock;
import java.util.Objects;

public final class RoomEvaluationStartService {
    private static final int MAX_BATCH_SIZE = 1_000;
    private final RoomEvaluationStartPort startPort;
    private final Clock clock;

    public RoomEvaluationStartService(RoomEvaluationStartPort startPort, Clock clock) {
        this.startPort = Objects.requireNonNull(startPort, "startPort");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public RoomEvaluationStartReport run(int limit) {
        if (limit < 1 || limit > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and 1000");
        }
        return startPort.startEligible(clock.instant(), limit);
    }
}
