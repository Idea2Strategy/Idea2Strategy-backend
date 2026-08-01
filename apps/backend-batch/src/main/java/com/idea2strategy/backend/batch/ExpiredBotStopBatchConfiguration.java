package com.idea2strategy.backend.batch;

import com.idea2strategy.backend.application.botcontrol.ExpiredBotStopBatchService;
import com.idea2strategy.backend.application.botcontrol.ExpiredBotStopCommandPort;
import com.idea2strategy.backend.application.botcontrol.ExpiredBotStopQueryPort;
import com.idea2strategy.backend.persistence.botcontrol.BotStopCommandJooqAdapter;
import com.idea2strategy.backend.persistence.botcontrol.ExpiredBotStopJooqQueryAdapter;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
        name = "idea2strategy.batch.expired-bot-stop.enabled",
        havingValue = "true",
        matchIfMissing = true)
@Import({ExpiredBotStopJooqQueryAdapter.class, BotStopCommandJooqAdapter.class})
class ExpiredBotStopBatchConfiguration {
    @Bean
    Clock batchClock() {
        return Clock.systemUTC();
    }

    @Bean
    ExpiredBotStopBatchService expiredBotStopBatchService(
            ExpiredBotStopQueryPort query, ExpiredBotStopCommandPort command, Clock batchClock) {
        return new ExpiredBotStopBatchService(query, command, batchClock);
    }

    @Bean
    ExpiredBotStopBatchRunner expiredBotStopBatchRunner(
            ExpiredBotStopBatchService service,
            @Value("${idea2strategy.batch.expired-bot-stop.batch-size:100}") int batchSize) {
        return new ExpiredBotStopBatchRunner(service, batchSize);
    }
}
