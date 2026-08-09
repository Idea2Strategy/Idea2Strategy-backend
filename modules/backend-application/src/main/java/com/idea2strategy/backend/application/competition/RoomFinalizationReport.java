package com.idea2strategy.backend.application.competition;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record RoomFinalizationReport(
        Instant observedAt,
        int roomsAttempted,
        int roomsFinalized,
        int participationsFinalized,
        List<RoomFinalizationFailure> failures) {
    public RoomFinalizationReport {
        Objects.requireNonNull(observedAt, "observedAt");
        failures = List.copyOf(Objects.requireNonNull(failures, "failures"));
        if (roomsAttempted < 0 || roomsFinalized < 0 || participationsFinalized < 0
                || roomsFinalized + failures.size() > roomsAttempted) {
            throw new IllegalArgumentException("room finalization report counts are invalid");
        }
    }
}
