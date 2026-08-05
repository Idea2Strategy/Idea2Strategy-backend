package com.idea2strategy.backend.api.backtest;

import com.idea2strategy.backend.application.backtest.CustomBacktestService;
import com.idea2strategy.backend.application.common.CurrentPrincipal;
import com.idea2strategy.backend.persistence.backtest.BacktestRequestOutboxStore;
import com.idea2strategy.backend.persistence.backtest.CustomBacktestJooqAdapter;
import com.idea2strategy.backend.persistence.backtest.FeatureMaterializationPinResolver;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(value = CurrentPrincipal.class, type = "org.jooq.DSLContext")
@Import({BacktestRequestOutboxStore.class, FeatureMaterializationPinResolver.class,
        CustomBacktestJooqAdapter.class})
public class CustomBacktestConfiguration {
    @Bean
    CustomBacktestService customBacktestService(
            CustomBacktestJooqAdapter adapter, CurrentPrincipal principal) {
        return new CustomBacktestService(adapter, principal, Clock.systemUTC());
    }
}
