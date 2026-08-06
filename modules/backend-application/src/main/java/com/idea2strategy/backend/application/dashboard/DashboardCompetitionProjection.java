package com.idea2strategy.backend.application.dashboard;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record DashboardCompetitionProjection(
        UUID roomId,
        String roomName,
        String roomStatus,
        String participationStatus,
        Instant evaluationEndsAt,
        String timezoneName) {
    public DashboardCompetitionProjection {
        Objects.requireNonNull(roomId, "roomId");
        Objects.requireNonNull(roomName, "roomName");
        Objects.requireNonNull(roomStatus, "roomStatus");
        Objects.requireNonNull(participationStatus, "participationStatus");
        Objects.requireNonNull(evaluationEndsAt, "evaluationEndsAt");
        Objects.requireNonNull(timezoneName, "timezoneName");
    }
}
