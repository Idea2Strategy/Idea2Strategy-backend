package com.idea2strategy.backend.application.competition;

import java.time.Instant;
import java.util.Objects;

public record RoomScheduleTransitionReport(Instant observedAt, int roomsAdvanced, int transitionsApplied) {
    public RoomScheduleTransitionReport {
        Objects.requireNonNull(observedAt, "observedAt");
        if (roomsAdvanced < 0 || transitionsApplied < roomsAdvanced) {
            throw new IllegalArgumentException("transition counts are invalid");
        }
    }
}
