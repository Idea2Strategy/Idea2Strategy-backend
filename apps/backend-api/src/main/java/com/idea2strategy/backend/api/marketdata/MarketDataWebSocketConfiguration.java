package com.idea2strategy.backend.api.marketdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idea2strategy.backend.application.strategy.BasicStrategyCatalogQueryService;
import com.idea2strategy.backend.messaging.marketdata.RedisDisplayPriceAdapter;
import com.idea2strategy.backend.messaging.marketdata.RedisSingleUseTicketStore;
import java.time.Clock;
import java.util.Arrays;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
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
            MeterRegistry meterRegistry,
            @Value("${market-data.websocket.allowed-origin-patterns}") String origins,
            @Value("${market-data.websocket.maximum-connections-per-account:5}") int maximumConnections,
            @Value("${market-data.websocket.maximum-subscriptions-per-connection:20}") int maximumSubscriptions) {
        this.handler = new MarketDataPriceWebSocketHandler(
                prices, catalog, mapper, maximumConnections, maximumSubscriptions, meterRegistry);
        this.tickets = new MarketDataTicketHandshakeInterceptor(ticketService);
        this.allowedOrigins = Arrays.stream(origins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .toArray(String[]::new);
        if (allowedOrigins.length == 0) {
            throw new IllegalArgumentException(
                    "market-data.websocket.allowed-origin-patterns must not be blank");
        }
    }

    @Bean(destroyMethod = "close")
    static RedisDisplayPriceAdapter redisDisplayPriceAdapter(
            @Value("${market-data.redis-uri}") String redisUri,
            @Value("${market-data.redis-key-prefix:i2s}") String keyPrefix) {
        return RedisDisplayPriceAdapter.connect(redisUri, keyPrefix);
    }

    @Bean(destroyMethod = "close")
    static RedisSingleUseTicketStore redisSingleUseTicketStore(
            @Value("${market-data.redis-uri}") String redisUri,
            @Value("${market-data.redis-key-prefix:i2s}") String keyPrefix) {
        return RedisSingleUseTicketStore.connect(redisUri, keyPrefix);
    }

    @Bean
    static MarketDataWebSocketTicketService marketDataWebSocketTicketService(
            RedisSingleUseTicketStore store) {
        return new MarketDataWebSocketTicketService(Clock.systemUTC(), store);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/v1/market-data/prices")
                .addInterceptors(tickets)
                .setAllowedOriginPatterns(allowedOrigins);
    }

    @PreDestroy
    void closeHandler() {
        handler.close();
    }
}
