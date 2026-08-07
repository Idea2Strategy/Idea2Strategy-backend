package com.idea2strategy.backend.api.marketdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.strategy.BasicStrategyCatalogQueryService;
import com.idea2strategy.backend.messaging.marketdata.RedisDisplayPriceAdapter;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration(proxyBeanMethods = false)
@EnableWebSocket
@ConditionalOnProperty(name = {"spring.datasource.url", "market-data.redis-uri"})
public class MarketDataWebSocketConfiguration implements WebSocketConfigurer {
    private final MarketDataPriceWebSocketHandler handler;
    private final MarketDataTicketHandshakeInterceptor tickets;
    private final String[] allowedOrigins;

    public MarketDataWebSocketConfiguration(
            RedisDisplayPriceAdapter prices,
            BasicStrategyCatalogQueryService catalog,
            ObjectMapper mapper,
            MarketDataWebSocketTicketService ticketService,
            @Value("${market-data.websocket.allowed-origin-patterns:*}") String origins) {
        this.handler = new MarketDataPriceWebSocketHandler(prices, catalog, mapper);
        this.tickets = new MarketDataTicketHandshakeInterceptor(ticketService);
        this.allowedOrigins = origins.split(",");
    }

    @Bean(destroyMethod = "close")
    static RedisDisplayPriceAdapter redisDisplayPriceAdapter(
            @Value("${market-data.redis-uri}") String redisUri,
            @Value("${market-data.redis-key-prefix:i2s}") String keyPrefix) {
        return RedisDisplayPriceAdapter.connect(redisUri, keyPrefix);
    }

    @Bean
    static MarketDataWebSocketTicketService marketDataWebSocketTicketService() {
        return new MarketDataWebSocketTicketService(Clock.systemUTC());
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/v1/market-data/prices")
                .addInterceptors(tickets)
                .setAllowedOriginPatterns(allowedOrigins);
    }
}
