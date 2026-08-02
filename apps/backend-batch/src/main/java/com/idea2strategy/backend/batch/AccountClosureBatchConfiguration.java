package com.idea2strategy.backend.batch;

import com.idea2strategy.backend.application.accountclosure.AccountClosureCoordinator;
import com.idea2strategy.backend.application.accountclosure.AccountClosureReadinessProbe;
import com.idea2strategy.backend.persistence.botcontrol.BotStopCommandJooqAdapter;
import com.idea2strategy.backend.persistence.identity.AccountClosureJpaStore;
import com.idea2strategy.backend.persistence.identity.AccountClosurePersistenceConfiguration;
import com.idea2strategy.backend.persistence.identity.AccountLifecycleJpaCommandAdapter;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
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
        name = "idea2strategy.batch.account-closure.enabled",
        havingValue = "true",
        matchIfMissing = false)
@Import({AccountClosurePersistenceConfiguration.class, AccountLifecycleJpaCommandAdapter.class,
        BotStopCommandJooqAdapter.class})
class AccountClosureBatchConfiguration {
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    Clock accountClosureClock() { return Clock.systemUTC(); }

    @Bean
    AccountClosureCoordinator accountClosureCoordinator(
            AccountClosureJpaStore store,
            List<AccountClosureReadinessProbe> probes,
            Clock clock,
            @Value("${idea2strategy.batch.account-closure.timeout:PT1H}") Duration timeout) {
        return new AccountClosureCoordinator(store, probes, store, clock, timeout);
    }

    @Bean
    AccountClosureBatchRunner accountClosureBatchRunner(
            AccountClosureCoordinator coordinator,
            @Value("${idea2strategy.batch.account-closure.batch-size:100}") int batchSize) {
        return new AccountClosureBatchRunner(coordinator, batchSize);
    }
}
