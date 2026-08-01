package com.idea2strategy.backend.api.botcontrol;

import com.idea2strategy.backend.application.botcontrol.BotExecutionPreflightService;
import com.idea2strategy.backend.application.common.CurrentPrincipal;
import com.idea2strategy.backend.persistence.botcontrol.BotExecutionPreflightJooqQueryAdapter;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(value = CurrentPrincipal.class, type = "org.jooq.DSLContext")
@Import(BotExecutionPreflightJooqQueryAdapter.class)
public class BotExecutionPreflightConfiguration {
    @Bean
    BotExecutionPreflightService botExecutionPreflightService(
            BotExecutionPreflightJooqQueryAdapter queryAdapter, CurrentPrincipal principal) {
        return new BotExecutionPreflightService(queryAdapter, principal, Clock.systemUTC());
    }
}
