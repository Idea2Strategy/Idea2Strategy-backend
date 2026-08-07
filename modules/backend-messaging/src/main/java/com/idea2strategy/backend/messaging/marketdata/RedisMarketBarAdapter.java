package com.idea2strategy.backend.messaging.marketdata;

import com.idea2strategy.backend.application.marketdata.MarketBar;
import com.idea2strategy.backend.application.marketdata.MarketBarPort;
import com.idea2strategy.backend.application.marketdata.MarketBarTimeframe;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class RedisMarketBarAdapter implements MarketBarPort, AutoCloseable {
    private final RedisClient client;
    private final StatefulRedisConnection<String, String> commands;
    private final MarketBarJsonCodec codec = new MarketBarJsonCodec();
    private final String keyPrefix;

    public static RedisMarketBarAdapter connect(String redisUri, String keyPrefix) {
        if (redisUri == null || redisUri.isBlank()) {
            throw new IllegalArgumentException("market-data.redis-uri must not be blank");
        }
        RedisClient client = RedisClient.create(redisUri);
        try {
            return new RedisMarketBarAdapter(client, keyPrefix);
        } catch (RuntimeException exception) {
            client.shutdown();
            throw exception;
        }
    }

    RedisMarketBarAdapter(RedisClient client, String keyPrefix) {
        this.client = Objects.requireNonNull(client, "client");
        this.keyPrefix = requirePrefix(keyPrefix);
        this.commands = client.connect();
    }

    @Override
    public List<MarketBar> findRecent(UUID instrumentId, MarketBarTimeframe timeframe, int limit) {
        Objects.requireNonNull(instrumentId, "instrumentId");
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000");
        }
        List<String> encoded = commands.sync().zrevrange(recentBarsKey(instrumentId, timeframe), 0, limit - 1L);
        List<MarketBar> bars = new ArrayList<>(encoded.size());
        encoded.forEach(value -> bars.add(codec.decode(value)));
        Collections.reverse(bars);
        return List.copyOf(bars);
    }

    String recentBarsKey(UUID instrumentId, MarketBarTimeframe timeframe) {
        return "{" + keyPrefix + ":market}:bars:" + instrumentId + ":" + timeframe.value();
    }

    private static String requirePrefix(String value) {
        if (value == null || value.isBlank() || value.contains("{") || value.contains("}")) {
            throw new IllegalArgumentException("market-data.redis-key-prefix must be a plain non-empty value");
        }
        return value;
    }

    @Override
    public void close() {
        commands.close();
        client.shutdown();
    }
}
