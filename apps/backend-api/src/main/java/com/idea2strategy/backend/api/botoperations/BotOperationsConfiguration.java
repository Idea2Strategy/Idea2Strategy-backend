package com.idea2strategy.backend.api.botoperations;

import com.idea2strategy.backend.application.botoperations.BotOperationsQueryService;
import com.idea2strategy.backend.application.common.CurrentPrincipal;
import com.idea2strategy.backend.persistence.botoperations.BotOperationsJooqQueryAdapter;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"spring.datasource.url", "identity.crypto.session-hmac-key"})
@Import(BotOperationsJooqQueryAdapter.class)
public class BotOperationsConfiguration {
    @Bean
    BotOperationsQueryService botOperationsQueryService(
            BotOperationsJooqQueryAdapter queryAdapter, CurrentPrincipal principal) {
        return new BotOperationsQueryService(queryAdapter, principal, Clock.systemUTC());
    }
}
