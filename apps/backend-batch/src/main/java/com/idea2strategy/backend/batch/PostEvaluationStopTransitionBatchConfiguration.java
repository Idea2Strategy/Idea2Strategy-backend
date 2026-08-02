package com.idea2strategy.backend.batch;

import com.idea2strategy.backend.application.competition.PostEvaluationStopTransitionService;
import com.idea2strategy.backend.persistence.botcontrol.BotStopCommandJooqAdapter;
import com.idea2strategy.backend.persistence.competition.PostEvaluationStopTransitionJooqAdapter;
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
        name = "idea2strategy.batch.post-evaluation-stop-transition.enabled",
        havingValue = "true",
        matchIfMissing = true)
@Import({PostEvaluationStopTransitionJooqAdapter.class, BotStopCommandJooqAdapter.class})
class PostEvaluationStopTransitionBatchConfiguration {
    @Bean
    PostEvaluationStopTransitionService postEvaluationStopTransitionService(
            PostEvaluationStopTransitionJooqAdapter adapter) {
        return new PostEvaluationStopTransitionService(adapter, Clock.systemUTC());
    }

    @Bean
    PostEvaluationStopTransitionBatchRunner postEvaluationStopTransitionBatchRunner(
            PostEvaluationStopTransitionService service,
            @Value("${idea2strategy.batch.post-evaluation-stop-transition.batch-size:100}") int batchSize) {
        return new PostEvaluationStopTransitionBatchRunner(service, batchSize);
    }
}
