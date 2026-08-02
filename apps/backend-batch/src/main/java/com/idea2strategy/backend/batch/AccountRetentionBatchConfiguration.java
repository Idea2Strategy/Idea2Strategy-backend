package com.idea2strategy.backend.batch;

import com.idea2strategy.backend.application.accountretention.AccountRetentionCoordinator;
import com.idea2strategy.backend.persistence.identity.AccountRetentionJpaAdapter;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
        name = "idea2strategy.batch.account-retention.enabled",
        havingValue = "true",
        matchIfMissing = false)
@Import(AccountRetentionJpaAdapter.class)
class AccountRetentionBatchConfiguration {
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock accountRetentionClock() {
        return Clock.systemUTC();
    }

    @Bean
    AccountRetentionCoordinator accountRetentionCoordinator(AccountRetentionJpaAdapter store, Clock clock) {
        return new AccountRetentionCoordinator(store, clock);
    }

    @Bean
    AccountRetentionBatchRunner accountRetentionBatchRunner(
            AccountRetentionCoordinator coordinator,
            @Value("${idea2strategy.batch.account-retention.batch-size:100}") int batchSize) {
        return new AccountRetentionBatchRunner(coordinator, batchSize);
    }
}
