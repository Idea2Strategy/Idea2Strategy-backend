package com.idea2strategy.backend.batch;

import com.idea2strategy.backend.application.identity.AccountLifecycleCandidateQueryPort;
import com.idea2strategy.backend.application.identity.AccountLifecycleCommandPort;
import com.idea2strategy.backend.application.identity.AccountLifecycleService;
import com.idea2strategy.backend.persistence.identity.AccountLifecycleJooqQueryAdapter;
import com.idea2strategy.backend.persistence.identity.AccountLifecycleJpaCommandAdapter;
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
        name = "idea2strategy.batch.account-dormancy.enabled",
        havingValue = "true",
        matchIfMissing = true)
@Import({AccountLifecycleJooqQueryAdapter.class, AccountLifecycleJpaCommandAdapter.class})
class AccountDormancyBatchConfiguration {
    @Bean
    AccountLifecycleService accountDormancyLifecycleService(
            AccountLifecycleCommandPort commands,
            AccountLifecycleCandidateQueryPort candidates) {
        return new AccountLifecycleService(commands, candidates, Clock.systemUTC());
    }

    @Bean
    AccountDormancyBatchRunner accountDormancyBatchRunner(
            AccountLifecycleService service,
            @Value("${idea2strategy.batch.account-dormancy.batch-size:100}") int batchSize) {
        return new AccountDormancyBatchRunner(service, batchSize);
    }
}
