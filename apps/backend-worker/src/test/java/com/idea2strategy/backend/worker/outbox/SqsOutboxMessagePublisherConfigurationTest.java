package com.idea2strategy.backend.worker.outbox;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;

class SqsOutboxMessagePublisherConfigurationTest {

    @Test
    void rejectsAnEnabledRouteWhoseQueueUrlWasNotProvided() {
        assertThatThrownBy(() -> new SqsOutboxMessagePublisher(
                mock(SqsClient.class), Map.of("OFFICIAL_BACKTEST_REQUESTED", "")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OFFICIAL_BACKTEST_REQUESTED")
                .hasMessageContaining("queue URL");
    }
}
