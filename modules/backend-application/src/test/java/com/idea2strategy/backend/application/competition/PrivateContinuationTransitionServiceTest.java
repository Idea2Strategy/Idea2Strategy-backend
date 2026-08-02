package com.idea2strategy.backend.application.competition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import org.junit.jupiter.api.Test;

class PrivateContinuationTransitionServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-02T12:00:00Z");

    @Test
    void appliesReadyTransitionsUntilTheBatchLimit() {
        var port = new StubPort(List.of(
                PrivateContinuationTransitionDecision.APPLIED,
                PrivateContinuationTransitionDecision.APPLIED,
                PrivateContinuationTransitionDecision.APPLIED));
        var service = new PrivateContinuationTransitionService(port, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(service.run(2)).isEqualTo(new PrivateContinuationTransitionReport(NOW, 2));
        assertThat(port.calls).isEqualTo(2);
    }

    @Test
    void stopsWhenNoEligibleCandidateRemains() {
        var port = new StubPort(List.of(
                PrivateContinuationTransitionDecision.APPLIED,
                PrivateContinuationTransitionDecision.NO_READY_CANDIDATE));
        var service = new PrivateContinuationTransitionService(port, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(service.run(10).transitionsApplied()).isEqualTo(1);
        assertThat(port.calls).isEqualTo(2);
    }

    @Test
    void rejectsANonPositiveLimitWithoutCallingPersistence() {
        var port = new StubPort(List.of());
        var service = new PrivateContinuationTransitionService(port, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.run(0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(port.calls).isZero();
    }

    private static final class StubPort implements PrivateContinuationTransitionPort {
        private final ArrayDeque<PrivateContinuationTransitionDecision> decisions;
        private int calls;

        private StubPort(List<PrivateContinuationTransitionDecision> decisions) {
            this.decisions = new ArrayDeque<>(decisions);
        }

        @Override
        public PrivateContinuationTransitionDecision transitionNext(Instant observedAt) {
            calls++;
            return decisions.removeFirst();
        }
    }
}
