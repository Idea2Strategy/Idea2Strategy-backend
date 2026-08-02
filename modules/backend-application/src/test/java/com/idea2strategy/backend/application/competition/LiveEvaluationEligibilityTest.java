package com.idea2strategy.backend.application.competition;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LiveEvaluationEligibilityTest {
    private static final UUID ROOM_ID = id(1);
    private static final UUID PARTICIPATION_ID = id(2);
    private static final UUID BOT_ID = id(3);
    private static final Instant OBSERVED_AT = Instant.parse("2026-08-02T06:00:00Z");

    @Test
    void requiresBothThresholdsAndAcceptsTheirExactBoundaries() {
        var eligible = evidence(3_600, 3_600, 5, 5);
        var ineligible = evidence(3_599, 3_600, 4, 5);

        assertThat(eligible.eligible()).isTrue();
        assertThat(eligible.reasons()).isEmpty();
        assertThat(ineligible.eligible()).isFalse();
        assertThat(ineligible.reasons()).containsExactly(
                LiveEvaluationIneligibilityReason.MINIMUM_OPERATION_NOT_MET,
                LiveEvaluationIneligibilityReason.MINIMUM_FILL_COUNT_NOT_MET);
    }

    @Test
    void treatsZeroThresholdsAsDisabledWithoutInventingScoreEvidence() {
        var eligibility = evidence(0, 0, 0, 0);

        assertThat(eligibility.eligible()).isTrue();
        assertThat(eligibility.operationSeconds()).isZero();
        assertThat(eligibility.fillCount()).isZero();
    }

    private static LiveEvaluationEligibility evidence(
            long operationSeconds,
            long requiredOperationSeconds,
            long fillCount,
            int requiredFillCount) {
        return LiveEvaluationEligibility.fromEvidence(
                ROOM_ID,
                PARTICIPATION_ID,
                BOT_ID,
                OBSERVED_AT,
                operationSeconds,
                requiredOperationSeconds,
                fillCount,
                requiredFillCount);
    }

    private static UUID id(int suffix) {
        return UUID.fromString("a8000000-0000-4000-8000-" + String.format("%012d", suffix));
    }
}
