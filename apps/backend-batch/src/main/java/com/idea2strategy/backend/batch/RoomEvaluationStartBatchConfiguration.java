package com.idea2strategy.backend.batch;

import com.idea2strategy.backend.application.competition.RoomEvaluationStartService;
import com.idea2strategy.backend.persistence.competition.RoomEvaluationStartJooqAdapter;
import com.idea2strategy.backend.persistence.backtest.FeatureMaterializationPinResolver;
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
        name = "idea2strategy.batch.room-evaluation-start.enabled",
        havingValue = "true",
        matchIfMissing = true)
@Import({FeatureMaterializationPinResolver.class, RoomEvaluationStartJooqAdapter.class})
class RoomEvaluationStartBatchConfiguration {
    @Bean
    RoomEvaluationStartService roomEvaluationStartService(RoomEvaluationStartJooqAdapter adapter) {
        return new RoomEvaluationStartService(adapter, Clock.systemUTC());
    }

    @Bean
    RoomEvaluationStartBatchRunner roomEvaluationStartBatchRunner(
            RoomEvaluationStartService service,
            @Value("${idea2strategy.batch.room-evaluation-start.batch-size:100}") int batchSize) {
        return new RoomEvaluationStartBatchRunner(service, batchSize);
    }
}
