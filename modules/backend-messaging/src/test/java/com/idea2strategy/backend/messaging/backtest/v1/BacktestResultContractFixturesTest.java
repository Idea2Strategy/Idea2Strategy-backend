package com.idea2strategy.backend.messaging.backtest.v1;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.messaging.competition.contract.RoomContractFixtures;
import com.idea2strategy.backend.messaging.performance.contract.LivePerformanceContractFixtures;
import com.idea2strategy.backend.messaging.performance.contract.LivePerformanceInputDecision;
import com.idea2strategy.backend.messaging.performance.contract.LivePerformanceInputValidator;
import org.junit.jupiter.api.Test;

class BacktestResultContractFixturesTest {
    @Test
    void pinsTheD93ProviderAndMatchesTheSourceThatERejects() throws Exception {
        var provider = BacktestResultContractFixtures.completed();
        var replayedProvider = BacktestResultContractFixtures.completed();
        byte[] resourceBytes;
        try (var stream = BacktestResultContractFixturesTest.class.getResourceAsStream(
                BacktestResultContractFixtures.COMPLETED_RESOURCE)) {
            assertThat(stream).isNotNull();
            resourceBytes = stream.readAllBytes();
        }
        var resource = new ObjectMapper().readTree(resourceBytes);
        var schedule = RoomContractFixtures.publicLiveRoomSchedule();
        var rejectedInput = LivePerformanceContractFixtures.backtestResultAt(
                schedule, schedule.evaluationStartsAt());

        assertThat(BacktestResultContractFixtures.PROVIDER_COMMIT)
                .isEqualTo("95d0d44d89b7d633b84b6d38b7236a78e1b547b1");
        assertThat(replayedProvider).isEqualTo(provider);
        assertThat(replayedProvider.metadata().idempotencyKey())
                .isEqualTo(provider.metadata().idempotencyKey());
        assertThat(resource.required("metadata").required("idempotencyKey").textValue())
                .isEqualTo(provider.metadata().idempotencyKey());
        assertThat(resource.required("backtestRunId").textValue())
                .isEqualTo(provider.backtestRunId().toString());
        assertThat(resource.required("resultHash").textValue()).isEqualTo(provider.resultHash());
        assertThat(resource.required("source").textValue()).isEqualTo(provider.source());
        assertThat(resource.required("eventType").textValue()).isEqualTo(provider.eventType());
        assertThat(resource.required("livePerformanceEligible").booleanValue())
                .isEqualTo(provider.livePerformanceEligible());
        assertThat(provider.source()).isEqualTo(rejectedInput.source().name());
        assertThat(provider.eventType()).isEqualTo(rejectedInput.eventType().name());
        assertThat(provider.livePerformanceEligible()).isFalse();
        assertThat(new LivePerformanceInputValidator().validate(
                        schedule, RoomContractFixtures.EVALUATION_SEGMENT_ID, rejectedInput))
                .isEqualTo(LivePerformanceInputDecision.BACKTEST_SOURCE_NOT_ALLOWED);
    }
}
