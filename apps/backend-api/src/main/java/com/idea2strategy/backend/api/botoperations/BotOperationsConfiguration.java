package com.idea2strategy.backend.api.botoperations;

import com.idea2strategy.backend.application.botoperations.BotOperationsQueryService;
import com.idea2strategy.backend.application.botoperations.BotDeletionCommandService;
import com.idea2strategy.backend.application.common.CurrentPrincipal;
import com.idea2strategy.backend.persistence.botoperations.BotOperationsJooqQueryAdapter;
import com.idea2strategy.backend.persistence.botoperations.BotDeletionJooqAdapter;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"spring.datasource.url", "identity.crypto.customer-jwt-signing-key"})
@Import({BotOperationsJooqQueryAdapter.class, BotDeletionJooqAdapter.class})
public class BotOperationsConfiguration {
    @Bean
    BotDeletionCommandService botDeletionCommandService(
            BotDeletionJooqAdapter deletionAdapter, CurrentPrincipal principal) {
        return new BotDeletionCommandService(deletionAdapter, principal, Clock.systemUTC());
    }

    @Bean
    BotOperationsQueryService botOperationsQueryService(
            BotOperationsJooqQueryAdapter queryAdapter, CurrentPrincipal principal) {
        return new BotOperationsQueryService(queryAdapter, principal, Clock.systemUTC());
    }
}
