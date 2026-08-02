package com.idea2strategy.backend.application.competition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RoomEvaluationStartServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-02T04:00:00Z");

    @Test
    void startsEligibleParticipantsUsingOneServerObservation() {
        var observedAt = new AtomicReference<Instant>();
        RoomEvaluationStartPort port = (at, limit) -> {
            observedAt.set(at);
            return new RoomEvaluationStartReport(at, limit);
        };
        var service = new RoomEvaluationStartService(port, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(service.run(25)).isEqualTo(new RoomEvaluationStartReport(NOW, 25));
        assertThat(observedAt).hasValue(NOW);
    }

    @Test
    void rejectsUnsafeBatchSizesBeforeWriting() {
        RoomEvaluationStartPort port = (at, limit) -> {
            throw new AssertionError("port must not be called");
        };
        var service = new RoomEvaluationStartService(port, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThatThrownBy(() -> service.run(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.run(1_001)).isInstanceOf(IllegalArgumentException.class);
    }
}
