package com.idea2strategy.backend.application.competition;

import java.time.Instant;
import java.util.UUID;

public interface LiveEvaluationEligibilityPort {
    LiveEvaluationEligibility evaluate(UUID participationId, Instant observedAt);
}
