package com.idea2strategy.backend.application.competition;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RoomScheduleTransitionServiceTest {
    @Test
    void delegatesOneServerTimestampToTheAtomicTransitionPort() {
        Instant now = Instant.parse("2026-08-02T02:00:00Z");
        var port = new CapturingPort();
        var service = new RoomScheduleTransitionService(port, Clock.fixed(now, ZoneOffset.UTC));

        assertThat(service.run(25)).isEqualTo(new RoomScheduleTransitionReport(now, 1, 2));
        assertThat(port.observedAt).isEqualTo(now);
        assertThat(port.limit).isEqualTo(25);
    }

    private static final class CapturingPort implements RoomScheduleTransitionPort {
        private Instant observedAt;
        private int limit;

        @Override
        public RoomScheduleTransitionReport advanceDue(Instant observedAt, int limit) {
            this.observedAt = observedAt;
            this.limit = limit;
            return new RoomScheduleTransitionReport(observedAt, 1, 2);
        }
    }
}
