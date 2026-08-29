package com.idea2strategy.backend.api.marketdata;

import com.idea2strategy.backend.application.common.CurrentPrincipal;
import com.idea2strategy.backend.application.marketdata.MarketBarService;
import com.idea2strategy.backend.application.marketdata.MarketBenchmarkCatalogPort;
import com.idea2strategy.backend.application.strategy.BasicStrategyCatalogQueryService;
import com.idea2strategy.backend.messaging.marketdata.RedisMarketBarAdapter;
import com.idea2strategy.backend.persistence.marketdata.MarketBenchmarkCatalogJooqAdapter;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = {"spring.datasource.url", "market-data.redis-uri"})
public class MarketBarConfiguration {
    @Bean
    MarketBenchmarkCatalogPort marketBenchmarkCatalogPort(DSLContext dsl) {
        return new MarketBenchmarkCatalogJooqAdapter(dsl);
    }

    @Bean(destroyMethod = "close")
    RedisMarketBarAdapter redisMarketBarAdapter(
            @Value("${market-data.redis-uri}") String redisUri,
            @Value("${market-data.redis-key-prefix:i2s}") String keyPrefix) {
        return RedisMarketBarAdapter.connect(redisUri, keyPrefix);
    }

    @Bean
    MarketBarService marketBarService(
            RedisMarketBarAdapter adapter,
            BasicStrategyCatalogQueryService catalog,
            MarketBenchmarkCatalogPort benchmarkCatalog,
            CurrentPrincipal principal) {
        return new MarketBarService(adapter, catalog, benchmarkCatalog, principal);
    }
}
