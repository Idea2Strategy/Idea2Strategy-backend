package com.idea2strategy.backend.persistence.identity;

import com.idea2strategy.backend.application.accountclosure.AccountClosureReadinessProbe;
import com.idea2strategy.backend.application.botcontrol.BotStopCommandPort;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AccountClosurePersistenceConfiguration {
    @Bean
    AccountClosureJpaStore accountClosureJpaStore(jakarta.persistence.EntityManager entityManager) {
        return new AccountClosureJpaStore(entityManager);
    }

    @Bean
    AccountRetentionJpaStore accountRetentionJpaStore(jakarta.persistence.EntityManager entityManager) {
        return new AccountRetentionJpaStore(entityManager);
    }

    @Bean
    AccountClosureReadinessProbe botAccountClosureReadinessProbe(DSLContext dsl, BotStopCommandPort stops) {
        return new BotAccountClosureReadinessProbe(dsl, stops);
    }

    @Bean
    AccountClosureReadinessProbe tradingAccountClosureReadinessProbe(DSLContext dsl) {
        return new TradingAccountClosureReadinessProbe(dsl);
    }

    @Bean
    AccountClosureReadinessProbe competitionAccountClosureReadinessProbe(DSLContext dsl) {
        return new CompetitionAccountClosureReadinessProbe(dsl);
    }

    @Bean
    AccountClosureReadinessProbe notificationAccountClosureReadinessProbe(DSLContext dsl) {
        return new NotificationAccountClosureReadinessProbe(dsl);
    }

    @Bean
    AccountClosureReadinessProbe integrationAccountClosureReadinessProbe(DSLContext dsl) {
        return new IntegrationAccountClosureReadinessProbe(dsl);
    }
}
