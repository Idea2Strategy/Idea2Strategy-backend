package com.idea2strategy.backend.worker.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class BacktestQueueRuntimeConfigurationTest {

    @Test
    void bindsTheOfficialReleaseEventToTheBasicLaneRuntimeQueue() throws Exception {
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource(
                "runtime", Map.of(
                        "BACKTEST_BASIC_QUEUE_URL", "https://sqs.example/basic",
                        "BACKTEST_CUSTOM_QUEUE_URL", "https://sqs.example/custom",
                        "BACKTEST_COMPETITION_QUEUE_URL", "https://sqs.example/competition",
                        "ROOM_LEDGER_OPENED_QUEUE_URL", "https://sqs.example/room-opened",
                        "ROOM_LEDGER_REJECTED_QUEUE_URL", "https://sqs.example/room-rejected")));
        for (var source : new YamlPropertySourceLoader().load(
                "backend-worker", new ClassPathResource("application.yaml"))) {
            environment.getPropertySources().addLast(source);
        }

        assertThat(environment.getProperty(
                "idea2strategy.outbox-relay.queues.OFFICIAL_BACKTEST_REQUESTED"))
                .isEqualTo("https://sqs.example/basic");
        assertThat(environment.getProperty(
                "idea2strategy.outbox-relay.queues.CUSTOM_BACKTEST_REQUESTED"))
                .isEqualTo("https://sqs.example/custom");
        assertThat(environment.getProperty(
                "idea2strategy.outbox-relay.queues.COMPETITION_BACKTEST_REQUESTED"))
                .isEqualTo("https://sqs.example/competition");
        assertThat(environment.getProperty(
                "idea2strategy.outbox-relay.queues.ROOM_EVALUATION_ACCOUNT_OPENED"))
                .isEqualTo("https://sqs.example/room-opened");
        assertThat(environment.getProperty(
                "idea2strategy.outbox-relay.queues.ROOM_EVALUATION_ACCOUNT_OPEN_REJECTED"))
                .isEqualTo("https://sqs.example/room-rejected");
        assertThat(environment.getProperty("idea2strategy.room-ledger-results.long-poll-seconds"))
                .isEqualTo("5");
    }
}
