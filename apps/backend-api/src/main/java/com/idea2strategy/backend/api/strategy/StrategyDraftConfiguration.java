package com.idea2strategy.backend.api.strategy;

import com.idea2strategy.backend.application.common.CurrentPrincipal;
import com.idea2strategy.backend.application.strategy.BasicStrategyDraftCommandService;
import com.idea2strategy.backend.application.strategy.BasicStructureCatalogQueryService;
import com.idea2strategy.backend.application.strategy.BasicStrategyValidationCommandService;
import com.idea2strategy.backend.application.strategy.SecureStrategyEditLeaseTokenGenerator;
import com.idea2strategy.backend.application.strategy.StrategyCopyCommandService;
import com.idea2strategy.backend.application.strategy.StrategyDocumentQueryService;
import com.idea2strategy.backend.application.strategy.StrategyEditLeaseService;
import com.idea2strategy.backend.application.strategy.StrategyValidationQueryService;
import com.idea2strategy.backend.application.strategy.StrategyReleaseInputCatalogQueryService;
import com.idea2strategy.backend.persistence.strategy.BasicStrategyDraftJpaCommandAdapter;
import com.idea2strategy.backend.persistence.strategy.BasicStructureCatalogJooqQueryAdapter;
import com.idea2strategy.backend.persistence.strategy.StrategyDocumentJpaEntity;
import com.idea2strategy.backend.persistence.strategy.StrategyDocumentJooqQueryAdapter;
import com.idea2strategy.backend.persistence.strategy.StrategyDocumentSpringDataRepository;
import com.idea2strategy.backend.persistence.strategy.StrategyEditLeaseJpaCommandAdapter;
import com.idea2strategy.backend.persistence.strategy.StrategyJpaEntity;
import com.idea2strategy.backend.persistence.strategy.StrategyJooqQueryAdapter;
import com.idea2strategy.backend.persistence.strategy.StrategySpringDataRepository;
import com.idea2strategy.backend.persistence.strategy.StrategyValidationRunJpaCommandAdapter;
import com.idea2strategy.backend.persistence.strategy.StrategyValidationRunJpaEntity;
import com.idea2strategy.backend.persistence.strategy.StrategyValidationRunJooqQueryAdapter;
import com.idea2strategy.backend.persistence.strategy.StrategyValidationRunSpringDataRepository;
import com.idea2strategy.backend.persistence.strategy.StrategyReleaseInputCatalogJooqQueryAdapter;
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
@ConditionalOnProperty(name = {"spring.datasource.url", "identity.crypto.customer-jwt-signing-key"})
@EntityScan(basePackageClasses = {
    StrategyJpaEntity.class,
    StrategyDocumentJpaEntity.class,
    StrategyValidationRunJpaEntity.class
})
@EnableJpaRepositories(basePackageClasses = {
    StrategySpringDataRepository.class,
    StrategyDocumentSpringDataRepository.class,
    StrategyValidationRunSpringDataRepository.class
})
@Import({
    BasicStrategyDraftJpaCommandAdapter.class,
    BasicStructureCatalogJooqQueryAdapter.class,
    StrategyJooqQueryAdapter.class,
    StrategyDocumentJooqQueryAdapter.class,
    StrategyEditLeaseJpaCommandAdapter.class,
    StrategyValidationRunJpaCommandAdapter.class,
    StrategyValidationRunJooqQueryAdapter.class,
    StrategyReleaseInputCatalogJooqQueryAdapter.class
})
public class StrategyDraftConfiguration {
    @Bean
    BasicStrategyDraftCommandService basicStrategyDraftCommandService(
            BasicStrategyDraftJpaCommandAdapter commandAdapter,
            StrategyJooqQueryAdapter strategyQueryAdapter,
            StrategyDocumentJooqQueryAdapter documentQueryAdapter,
            CurrentPrincipal principal,
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
            CurrentPrincipal principal) {
        return new StrategyDocumentQueryService(documentQueryAdapter, principal);
    }

    @Bean
    StrategyEditLeaseService strategyEditLeaseService(
            StrategyEditLeaseJpaCommandAdapter leaseAdapter,
            StrategyJooqQueryAdapter strategyQueryAdapter,
            CurrentPrincipal principal,
            @Value("${strategy.edit-lease.duration:PT2M}") Duration leaseDuration) {
        return new StrategyEditLeaseService(
                leaseAdapter,
                strategyQueryAdapter,
                principal,
                new SecureStrategyEditLeaseTokenGenerator(),
                Clock.systemUTC(),
                leaseDuration);
    }

    @Bean
    BasicStructureCatalogQueryService basicStructureCatalogQueryService(
            BasicStructureCatalogJooqQueryAdapter queryAdapter) {
        return new BasicStructureCatalogQueryService(queryAdapter, Clock.systemUTC());
    }

    @Bean
    StrategyCopyCommandService strategyCopyCommandService(
            BasicStrategyDraftJpaCommandAdapter commandAdapter,
            StrategyJooqQueryAdapter strategyQueryAdapter,
            StrategyDocumentJooqQueryAdapter documentQueryAdapter,
            BasicStructureCatalogQueryService structureCatalogService,
            CurrentPrincipal principal,
            ApplicationEventPublisher eventPublisher) {
        return new StrategyCopyCommandService(
                commandAdapter,
                strategyQueryAdapter,
                documentQueryAdapter,
                structureCatalogService,
                principal,
                UUID::randomUUID,
                Clock.systemUTC(),
                eventPublisher::publishEvent);
    }

    @Bean
    BasicStrategyValidationCommandService basicStrategyValidationCommandService(
            StrategyValidationRunJpaCommandAdapter validationCommandAdapter,
            StrategyJooqQueryAdapter strategyQueryAdapter,
            StrategyDocumentJooqQueryAdapter documentQueryAdapter,
            CurrentPrincipal principal) {
        return new BasicStrategyValidationCommandService(
                validationCommandAdapter,
                strategyQueryAdapter,
                documentQueryAdapter,
                principal,
                UUID::randomUUID,
                Clock.systemUTC());
    }

    @Bean
    StrategyValidationQueryService strategyValidationQueryService(
            StrategyValidationRunJooqQueryAdapter validationQueryAdapter,
            StrategyDocumentJooqQueryAdapter documentQueryAdapter,
            CurrentPrincipal principal) {
        return new StrategyValidationQueryService(validationQueryAdapter, documentQueryAdapter, principal);
    }

    @Bean
    StrategyReleaseInputCatalogQueryService strategyReleaseInputCatalogQueryService(
            StrategyReleaseInputCatalogJooqQueryAdapter queryAdapter) {
        return new StrategyReleaseInputCatalogQueryService(queryAdapter, Clock.systemUTC());
    }
}
