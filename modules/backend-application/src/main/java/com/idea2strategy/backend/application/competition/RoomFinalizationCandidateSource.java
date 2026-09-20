package com.idea2strategy.backend.application.competition;

import java.util.Objects;
import java.util.UUID;

public record RoomFinalizationCandidateSource(
        UUID participationId,
        UUID evaluationSegmentId,
        UUID performanceSnapshotId,
        long scheduledEvaluationSeconds,
        long actualOperationSeconds,
        int actualFillCount,
        long baseRequiredOperationSeconds,
        int baseRequiredFillCount) {
    public RoomFinalizationCandidateSource {
        Objects.requireNonNull(participationId, "participationId");
        Objects.requireNonNull(evaluationSegmentId, "evaluationSegmentId");
        Objects.requireNonNull(performanceSnapshotId, "performanceSnapshotId");
        if (scheduledEvaluationSeconds <= 0
                || actualOperationSeconds < 0
                || actualFillCount < 0
                || baseRequiredOperationSeconds < 0
                || baseRequiredFillCount < 0) {
            throw new IllegalArgumentException("room finalization eligibility evidence is invalid");
        }
    }
}
