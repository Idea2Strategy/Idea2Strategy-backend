package com.idea2strategy.backend.api.bottrading;

import com.idea2strategy.backend.application.bottrading.BotTradingQueryService;
import com.idea2strategy.backend.application.common.CurrentPrincipal;
import com.idea2strategy.backend.persistence.bottrading.BotTradingJooqQueryAdapter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(value = CurrentPrincipal.class, type = "org.jooq.DSLContext")
@Import(BotTradingJooqQueryAdapter.class)
public class BotTradingConfiguration {
    @Bean
    BotTradingQueryService botTradingQueryService(
            BotTradingJooqQueryAdapter queryAdapter, CurrentPrincipal principal) {
        return new BotTradingQueryService(queryAdapter, principal);
    }
}
