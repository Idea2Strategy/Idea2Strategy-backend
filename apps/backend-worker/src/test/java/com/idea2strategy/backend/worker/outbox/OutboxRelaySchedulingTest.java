package com.idea2strategy.backend.worker.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

class OutboxRelaySchedulingTest {

    @Test
    void permitsTheFirstScheduledCycleToBeDelayed() throws Exception {
        Scheduled schedule = OutboxRelayConfiguration.OutboxRelayWorker.class
                .getDeclaredMethod("relay")
                .getAnnotation(Scheduled.class);

        assertThat(schedule.initialDelayString())
                .isEqualTo("${idea2strategy.outbox-relay.initial-delay:PT0S}");
    }
}
