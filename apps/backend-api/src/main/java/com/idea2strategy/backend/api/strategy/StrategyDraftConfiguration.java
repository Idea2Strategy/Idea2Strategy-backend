package com.idea2strategy.backend.api.strategy;

import com.idea2strategy.backend.application.common.CurrentSessionPrincipal;
import com.idea2strategy.backend.application.strategy.BasicStrategyDraftCommandService;
import com.idea2strategy.backend.application.strategy.SecureStrategyEditLeaseTokenGenerator;
import com.idea2strategy.backend.application.strategy.StrategyDocumentQueryService;
import com.idea2strategy.backend.application.strategy.StrategyEditLeaseService;
import com.idea2strategy.backend.persistence.strategy.BasicStrategyDraftJpaCommandAdapter;
import com.idea2strategy.backend.persistence.strategy.StrategyDocumentJpaEntity;
import com.idea2strategy.backend.persistence.strategy.StrategyDocumentJooqQueryAdapter;
import com.idea2strategy.backend.persistence.strategy.StrategyDocumentSpringDataRepository;
import com.idea2strategy.backend.persistence.strategy.StrategyEditLeaseJpaCommandAdapter;
import com.idea2strategy.backend.persistence.strategy.StrategyJpaEntity;
import com.idea2strategy.backend.persistence.strategy.StrategyJooqQueryAdapter;
import com.idea2strategy.backend.persistence.strategy.StrategySpringDataRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"spring.datasource.url", "identity.crypto.session-hmac-key"})
@EntityScan(basePackageClasses = {StrategyJpaEntity.class, StrategyDocumentJpaEntity.class})
@EnableJpaRepositories(basePackageClasses = {
    StrategySpringDataRepository.class,
    StrategyDocumentSpringDataRepository.class
})
@Import({
    BasicStrategyDraftJpaCommandAdapter.class,
    StrategyJooqQueryAdapter.class,
    StrategyDocumentJooqQueryAdapter.class,
    StrategyEditLeaseJpaCommandAdapter.class
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

    @Bean
    StrategyDocumentQueryService strategyDocumentQueryService(
            StrategyDocumentJooqQueryAdapter documentQueryAdapter,
            CurrentSessionPrincipal principal) {
        return new StrategyDocumentQueryService(documentQueryAdapter, principal);
    }

    @Bean
    StrategyEditLeaseService strategyEditLeaseService(
            StrategyEditLeaseJpaCommandAdapter leaseAdapter,
            StrategyJooqQueryAdapter strategyQueryAdapter,
            CurrentSessionPrincipal principal,
            @Value("${strategy.edit-lease.duration:PT2M}") Duration leaseDuration) {
        return new StrategyEditLeaseService(
                leaseAdapter,
                strategyQueryAdapter,
                principal,
                new SecureStrategyEditLeaseTokenGenerator(),
                Clock.systemUTC(),
                leaseDuration);
    }
}
