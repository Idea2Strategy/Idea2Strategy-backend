package com.idea2strategy.backend.batch;

import com.idea2strategy.backend.application.identity.PendingRegistrationCleanupService;
import com.idea2strategy.backend.persistence.identity.PendingRegistrationCleanupJpaAdapter;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
        name = "idea2strategy.batch.pending-registration-cleanup.enabled",
        havingValue = "true",
        matchIfMissing = true)
@Import(PendingRegistrationCleanupJpaAdapter.class)
class PendingRegistrationCleanupBatchConfiguration {
    @Bean
    PendingRegistrationCleanupService pendingRegistrationCleanupService(
            PendingRegistrationCleanupJpaAdapter cleanup,
            @Value("${idea2strategy.batch.pending-registration-cleanup.retention:P7D}") Duration retention) {
        return new PendingRegistrationCleanupService(cleanup, Clock.systemUTC(), retention);
    }

    @Bean
    PendingRegistrationCleanupBatchRunner pendingRegistrationCleanupBatchRunner(
            PendingRegistrationCleanupService cleanup,
            @Value("${idea2strategy.batch.pending-registration-cleanup.batch-size:250}") int batchSize) {
        return new PendingRegistrationCleanupBatchRunner(cleanup, batchSize);
    }
}
