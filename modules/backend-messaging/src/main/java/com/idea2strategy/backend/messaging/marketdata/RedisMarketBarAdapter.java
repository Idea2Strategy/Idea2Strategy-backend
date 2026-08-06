package com.idea2strategy.backend.messaging.marketdata;

import com.idea2strategy.backend.application.marketdata.MarketBar;
import com.idea2strategy.backend.application.marketdata.MarketBarPort;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class RedisMarketBarAdapter implements MarketBarPort, AutoCloseable {
    private final RedisClient client;
    private final StatefulRedisConnection<String, String> commands;
    private final StatefulRedisPubSubConnection<String, String> updates;
    private final MarketBarJsonCodec codec = new MarketBarJsonCodec();
    private final String keyPrefix;
    private final Map<UUID, CopyOnWriteArrayList<Consumer<MarketBar>>> listeners = new ConcurrentHashMap<>();

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
        this.updates = client.connectPubSub();
        this.updates.addListener(new RedisPubSubAdapter<>() {
            @Override
            public void message(String channel, String message) {
                if (!barUpdatesChannel().equals(channel)) {
                    return;
                }
                try {
                    MarketBar bar = codec.decode(message);
                    listeners.getOrDefault(bar.instrumentId(), new CopyOnWriteArrayList<>())
                            .forEach(listener -> listener.accept(bar));
                } catch (RuntimeException ignored) {
                    // A malformed provider event must not terminate the shared subscription.
                }
            }
        });
        this.updates.sync().subscribe(barUpdatesChannel());
    }

    @Override
    public List<MarketBar> findRecent(UUID instrumentId, int limit) {
        Objects.requireNonNull(instrumentId, "instrumentId");
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000");
        }
        List<String> encoded = commands.sync().zrevrange(recentBarsKey(instrumentId), 0, limit - 1L);
        List<MarketBar> bars = new ArrayList<>(encoded.size());
        encoded.forEach(value -> bars.add(codec.decode(value)));
        Collections.reverse(bars);
        return List.copyOf(bars);
    }

    @Override
    public AutoCloseable subscribe(UUID instrumentId, Consumer<MarketBar> listener) {
        Objects.requireNonNull(instrumentId, "instrumentId");
        Objects.requireNonNull(listener, "listener");
        CopyOnWriteArrayList<Consumer<MarketBar>> instrumentListeners =
                listeners.computeIfAbsent(instrumentId, ignored -> new CopyOnWriteArrayList<>());
        instrumentListeners.add(listener);
        return () -> {
            instrumentListeners.remove(listener);
            if (instrumentListeners.isEmpty()) {
                listeners.remove(instrumentId, instrumentListeners);
            }
        };
    }

    String recentBarsKey(UUID instrumentId) {
        return "{" + keyPrefix + ":market}:bars:" + instrumentId + ":1m";
    }

    String barUpdatesChannel() {
        return "{" + keyPrefix + ":market}:bar-updates";
    }

    private static String requirePrefix(String value) {
        if (value == null || value.isBlank() || value.contains("{") || value.contains("}")) {
            throw new IllegalArgumentException("market-data.redis-key-prefix must be a plain non-empty value");
        }
        return value;
    }

    @Override
    public void close() {
        updates.close();
        commands.close();
        client.shutdown();
    }
}
