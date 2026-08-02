package com.idea2strategy.backend.application.competition;

import java.time.Instant;
import java.util.UUID;

public record RoomLeaderboardSummary(
        UUID roomId,
        String name,
        String competitionType,
        String organizerType,
        String accessType,
        String status,
        Instant evaluationStartsAt,
        Instant evaluationEndsAt,
        Instant endedAt,
        UUID scoringTemplateVersionId,
        String rulesHash) {}
