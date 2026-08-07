package com.idea2strategy.backend.api.strategy;

import com.idea2strategy.backend.application.strategy.BacktestDataCoverageQueryService;
import com.idea2strategy.backend.application.strategy.BasicStrategyCatalogQueryService;
import com.idea2strategy.backend.persistence.strategy.BacktestDataCoverageJooqQueryAdapter;
import com.idea2strategy.backend.persistence.strategy.BasicStrategyCatalogJooqQueryAdapter;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.datasource", name = "url")
@Import({BasicStrategyCatalogJooqQueryAdapter.class, BacktestDataCoverageJooqQueryAdapter.class})
public class BasicStrategyCatalogConfiguration {
    @Bean
    BasicStrategyCatalogQueryService basicStrategyCatalogQueryService(
            BasicStrategyCatalogJooqQueryAdapter queryAdapter,
            @Value("${strategy.catalog.market-zone:America/New_York}") String marketZone) {
        return new BasicStrategyCatalogQueryService(queryAdapter, Clock.systemUTC(), ZoneId.of(marketZone));
    }

    @Bean
    BacktestDataCoverageQueryService backtestDataCoverageQueryService(
            BacktestDataCoverageJooqQueryAdapter queryAdapter) {
        return new BacktestDataCoverageQueryService(queryAdapter, Clock.systemUTC());
    }
}
