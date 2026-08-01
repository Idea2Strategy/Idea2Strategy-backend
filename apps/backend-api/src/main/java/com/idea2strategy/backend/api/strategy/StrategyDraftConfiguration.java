package com.idea2strategy.backend.api.strategy;

import com.idea2strategy.backend.application.common.CurrentSessionPrincipal;
import com.idea2strategy.backend.application.strategy.BasicStrategyDraftCommandService;
import com.idea2strategy.backend.persistence.strategy.BasicStrategyDraftJpaCommandAdapter;
import com.idea2strategy.backend.persistence.strategy.StrategyDocumentJpaEntity;
import com.idea2strategy.backend.persistence.strategy.StrategyDocumentJooqQueryAdapter;
import com.idea2strategy.backend.persistence.strategy.StrategyDocumentSpringDataRepository;
import com.idea2strategy.backend.persistence.strategy.StrategyJpaEntity;
import com.idea2strategy.backend.persistence.strategy.StrategyJooqQueryAdapter;
import com.idea2strategy.backend.persistence.strategy.StrategySpringDataRepository;
import java.time.Clock;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(
        value = CurrentSessionPrincipal.class,
        type = {"org.jooq.DSLContext", "org.springframework.jdbc.core.JdbcTemplate"})
@EntityScan(basePackageClasses = {StrategyJpaEntity.class, StrategyDocumentJpaEntity.class})
@EnableJpaRepositories(basePackageClasses = {
    StrategySpringDataRepository.class,
    StrategyDocumentSpringDataRepository.class
})
@Import({
    BasicStrategyDraftJpaCommandAdapter.class,
    StrategyJooqQueryAdapter.class,
    StrategyDocumentJooqQueryAdapter.class
})
public class StrategyDraftConfiguration {
    @Bean
    BasicStrategyDraftCommandService basicStrategyDraftCommandService(
            BasicStrategyDraftJpaCommandAdapter commandAdapter,
            StrategyJooqQueryAdapter strategyQueryAdapter,
            StrategyDocumentJooqQueryAdapter documentQueryAdapter,
            CurrentSessionPrincipal principal,
            ApplicationEventPublisher eventPublisher) {
        return new BasicStrategyDraftCommandService(
                commandAdapter,
                strategyQueryAdapter,
                documentQueryAdapter,
                principal,
                UUID::randomUUID,
                Clock.systemUTC(),
                eventPublisher::publishEvent);
    }
}
