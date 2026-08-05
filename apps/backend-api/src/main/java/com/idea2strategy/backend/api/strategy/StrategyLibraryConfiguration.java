package com.idea2strategy.backend.api.strategy;

import com.idea2strategy.backend.application.common.CurrentPrincipal;
import com.idea2strategy.backend.application.strategy.StrategyLibraryQueryService;
import com.idea2strategy.backend.persistence.strategy.StrategyLibraryJooqQueryAdapter;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"spring.datasource.url", "identity.crypto.session-hmac-key"})
@Import(StrategyLibraryJooqQueryAdapter.class)
public class StrategyLibraryConfiguration {
    @Bean
    StrategyLibraryQueryService strategyLibraryQueryService(
            StrategyLibraryJooqQueryAdapter queryAdapter, CurrentPrincipal principal) {
        return new StrategyLibraryQueryService(queryAdapter, principal, Clock.systemUTC());
    }
}
