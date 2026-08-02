package com.idea2strategy.backend.batch;

import com.idea2strategy.backend.application.competition.PrivateContinuationTransitionService;
import com.idea2strategy.backend.persistence.competition.PrivateContinuationTransitionJooqAdapter;
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
        name = "idea2strategy.batch.private-continuation-transition.enabled",
        havingValue = "true",
        matchIfMissing = true)
@Import(PrivateContinuationTransitionJooqAdapter.class)
class PrivateContinuationTransitionBatchConfiguration {
    @Bean
    PrivateContinuationTransitionService privateContinuationTransitionService(
            PrivateContinuationTransitionJooqAdapter adapter) {
        return new PrivateContinuationTransitionService(adapter, Clock.systemUTC());
    }

    @Bean
    PrivateContinuationTransitionBatchRunner privateContinuationTransitionBatchRunner(
            PrivateContinuationTransitionService service,
            @Value("${idea2strategy.batch.private-continuation-transition.batch-size:100}") int batchSize) {
        return new PrivateContinuationTransitionBatchRunner(service, batchSize);
    }
}
