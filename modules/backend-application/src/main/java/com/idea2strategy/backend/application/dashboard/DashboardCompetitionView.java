package com.idea2strategy.backend.application.dashboard;

import java.time.Instant;
import java.util.UUID;

public record DashboardCompetitionView(
        UUID roomId,
        String roomName,
        String roomStatus,
        String participationStatus,
        Instant evaluationEndsAt,
        String timezoneName) {}
