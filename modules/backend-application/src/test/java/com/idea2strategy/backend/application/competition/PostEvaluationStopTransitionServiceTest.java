package com.idea2strategy.backend.application.competition;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import org.junit.jupiter.api.Test;

class PostEvaluationStopTransitionServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-02T13:00:00Z");

    @Test
    void dispatchesReadyStopsUntilNoCandidateRemains() {
        var decisions = new ArrayDeque<>(List.of(
                PostEvaluationStopTransitionDecision.APPLIED,
                PostEvaluationStopTransitionDecision.NO_READY_CANDIDATE));
        var service = new PostEvaluationStopTransitionService(
                observedAt -> decisions.removeFirst(), Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(service.run(10)).isEqualTo(new PostEvaluationStopTransitionReport(NOW, 1));
        assertThat(decisions).isEmpty();
    }
}
