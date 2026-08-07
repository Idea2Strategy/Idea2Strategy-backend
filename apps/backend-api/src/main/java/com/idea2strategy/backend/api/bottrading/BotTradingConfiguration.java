package com.idea2strategy.backend.api.bottrading;

import com.idea2strategy.backend.application.bottrading.BotTradingQueryService;
import com.idea2strategy.backend.application.common.CurrentPrincipal;
import com.idea2strategy.backend.persistence.bottrading.BotTradingJooqQueryAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"spring.datasource.url", "identity.crypto.customer-jwt-signing-key"})
@Import(BotTradingJooqQueryAdapter.class)
public class BotTradingConfiguration {
    @Bean
    BotTradingQueryService botTradingQueryService(
            BotTradingJooqQueryAdapter queryAdapter, CurrentPrincipal principal) {
        return new BotTradingQueryService(queryAdapter, principal);
    }
}
