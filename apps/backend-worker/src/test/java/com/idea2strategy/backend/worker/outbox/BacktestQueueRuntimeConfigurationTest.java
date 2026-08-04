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
                "runtime", Map.of("BACKTEST_BASIC_QUEUE_URL", "https://sqs.example/basic")));
        for (var source : new YamlPropertySourceLoader().load(
                "backend-worker", new ClassPathResource("application.yaml"))) {
            environment.getPropertySources().addLast(source);
        }

        assertThat(environment.getProperty(
                "idea2strategy.outbox-relay.queues.OFFICIAL_BACKTEST_REQUESTED"))
                .isEqualTo("https://sqs.example/basic");
    }
}
