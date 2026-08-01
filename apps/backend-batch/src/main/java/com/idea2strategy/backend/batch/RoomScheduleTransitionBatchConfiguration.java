package com.idea2strategy.backend.batch;

import com.idea2strategy.backend.application.competition.RoomScheduleTransitionService;
import com.idea2strategy.backend.persistence.competition.RoomScheduleTransitionJooqAdapter;
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
        name = "idea2strategy.batch.room-schedule-transition.enabled",
        havingValue = "true",
        matchIfMissing = true)
@Import(RoomScheduleTransitionJooqAdapter.class)
class RoomScheduleTransitionBatchConfiguration {
    @Bean
    RoomScheduleTransitionService roomScheduleTransitionService(RoomScheduleTransitionJooqAdapter adapter) {
        return new RoomScheduleTransitionService(adapter, Clock.systemUTC());
    }

    @Bean
    RoomScheduleTransitionBatchRunner roomScheduleTransitionBatchRunner(
            RoomScheduleTransitionService service,
            @Value("${idea2strategy.batch.room-schedule-transition.batch-size:100}") int batchSize) {
        return new RoomScheduleTransitionBatchRunner(service, batchSize);
    }
}
