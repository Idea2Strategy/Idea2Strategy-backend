package com.idea2strategy.backend.api.strategy;

import com.idea2strategy.backend.application.strategy.BasicStrategyCatalogQueryService;
import com.idea2strategy.backend.persistence.strategy.BasicStrategyCatalogJooqQueryAdapter;
import java.time.Clock;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@ConditionalOnBean(type = "org.jooq.DSLContext")
@Import(BasicStrategyCatalogJooqQueryAdapter.class)
public class BasicStrategyCatalogConfiguration {
    @Bean
    BasicStrategyCatalogQueryService basicStrategyCatalogQueryService(
            BasicStrategyCatalogJooqQueryAdapter queryAdapter,
            @Value("${strategy.catalog.market-zone:America/New_York}") String marketZone) {
        return new BasicStrategyCatalogQueryService(queryAdapter, Clock.systemUTC(), ZoneId.of(marketZone));
    }
}
