package com.idea2strategy.backend.application.competition;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public record OfficialScoringEligibility(
        BigDecimal coverage,
        long requiredOperationSeconds,
        int requiredFillCount,
        boolean eligible,
        List<OfficialScoringIneligibilityReason> reasons) {
    public OfficialScoringEligibility {
        Objects.requireNonNull(coverage, "coverage");
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
        if (requiredOperationSeconds < 0 || requiredFillCount < 0) {
            throw new IllegalArgumentException("adjusted requirements must not be negative");
        }
        if (eligible != reasons.isEmpty()) {
            throw new IllegalArgumentException("eligibility and reasons disagree");
        }
    }
}
