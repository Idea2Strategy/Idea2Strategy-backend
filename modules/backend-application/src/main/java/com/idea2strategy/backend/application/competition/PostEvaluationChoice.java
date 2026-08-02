package com.idea2strategy.backend.application.competition;

import java.time.Instant;
import java.util.UUID;

public record PostEvaluationChoice(
        UUID roomId,
        UUID participationId,
        PostEvaluationAction action,
        Instant recordedAt,
        Instant lockedAt) {}
