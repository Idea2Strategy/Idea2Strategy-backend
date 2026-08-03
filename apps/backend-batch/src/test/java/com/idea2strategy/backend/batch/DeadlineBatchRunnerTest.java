package com.idea2strategy.backend.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

class DeadlineBatchRunnerTest {
    @Test
    void productionSchedulerDefaultsDisabled() {
        ConditionalOnProperty gate = DeadlineBatchConfiguration.class
                .getAnnotation(ConditionalOnProperty.class);

        assertThat(gate.name()).containsExactly("idea2strategy.batch.deadline.enabled");
        assertThat(gate.havingValue()).isEqualTo("true");
        assertThat(gate.matchIfMissing()).isFalse();
    }

    @Test
    void scheduledInvocationUsesOnlyTheDurableRuntimeBoundary() {
        DurableDeadlineBatchRuntime runtime = mock(DurableDeadlineBatchRuntime.class);
        when(runtime.run()).thenReturn(new DurableDeadlineBatchRuntime.RunReport(
                UUID.randomUUID(), UUID.randomUUID(), "trigger", false,
                1, 1, 1, 0, 0, 0, 0, "SUCCEEDED"));

        new DeadlineBatchRunner(runtime).run();

        verify(runtime).run();
    }
}
