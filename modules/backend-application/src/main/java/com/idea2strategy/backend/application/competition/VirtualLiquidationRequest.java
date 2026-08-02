package com.idea2strategy.backend.application.competition;

import java.util.Objects;
import java.util.UUID;

public record VirtualLiquidationRequest(UUID participationId, UUID evaluationSegmentId) {
    public VirtualLiquidationRequest {
        Objects.requireNonNull(participationId, "participationId");
        Objects.requireNonNull(evaluationSegmentId, "evaluationSegmentId");
    }
}
