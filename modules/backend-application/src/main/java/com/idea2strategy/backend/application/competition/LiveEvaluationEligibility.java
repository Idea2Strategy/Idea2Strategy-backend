package com.idea2strategy.backend.application.competition;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record LiveEvaluationEligibility(
        UUID roomId,
        UUID participationId,
        UUID botId,
        Instant observedAt,
        long operationSeconds,
        long requiredOperationSeconds,
        long fillCount,
        int requiredFillCount,
        boolean eligible,
        List<LiveEvaluationIneligibilityReason> reasons) {

    public LiveEvaluationEligibility {
        Objects.requireNonNull(roomId, "roomId");
        Objects.requireNonNull(participationId, "participationId");
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(observedAt, "observedAt");
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
        if (operationSeconds < 0 || requiredOperationSeconds < 0 || fillCount < 0 || requiredFillCount < 0) {
            throw new IllegalArgumentException("Live evaluation eligibility evidence must be nonnegative");
        }
        if (eligible != reasons.isEmpty()) {
            throw new IllegalArgumentException("Eligibility and reason evidence disagree");
        }
    }

    public static LiveEvaluationEligibility fromEvidence(
            UUID roomId,
            UUID participationId,
            UUID botId,
            Instant observedAt,
            long operationSeconds,
            long requiredOperationSeconds,
            long fillCount,
            int requiredFillCount) {
        List<LiveEvaluationIneligibilityReason> reasons = new ArrayList<>(2);
        if (operationSeconds < requiredOperationSeconds) {
            reasons.add(LiveEvaluationIneligibilityReason.MINIMUM_OPERATION_NOT_MET);
        }
        if (fillCount < requiredFillCount) {
            reasons.add(LiveEvaluationIneligibilityReason.MINIMUM_FILL_COUNT_NOT_MET);
        }
        return new LiveEvaluationEligibility(
                roomId,
                participationId,
                botId,
                observedAt,
                operationSeconds,
                requiredOperationSeconds,
                fillCount,
                requiredFillCount,
                reasons.isEmpty(),
                reasons);
    }
}
