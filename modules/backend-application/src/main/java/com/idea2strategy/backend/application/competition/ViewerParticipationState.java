package com.idea2strategy.backend.application.competition;

import java.time.Instant;

public record ViewerParticipationState(
        String anonymousAlias,
        String status,
        Instant joinedAt,
        Instant evaluationStartedAt,
        Instant evaluationFinishedAt) {}
