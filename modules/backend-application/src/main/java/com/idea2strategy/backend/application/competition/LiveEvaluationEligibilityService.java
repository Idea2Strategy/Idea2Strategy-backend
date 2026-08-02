package com.idea2strategy.backend.application.competition;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class LiveEvaluationEligibilityService {
    private final LiveEvaluationEligibilityPort port;

    public LiveEvaluationEligibilityService(LiveEvaluationEligibilityPort port) {
        this.port = Objects.requireNonNull(port, "port");
    }

    public LiveEvaluationEligibility evaluate(UUID participationId, Instant observedAt) {
        return port.evaluate(
                Objects.requireNonNull(participationId, "participationId"),
                Objects.requireNonNull(observedAt, "observedAt"));
    }
}
