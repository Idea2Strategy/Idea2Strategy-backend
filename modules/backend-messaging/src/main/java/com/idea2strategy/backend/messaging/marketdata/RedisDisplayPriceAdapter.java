package com.idea2strategy.backend.messaging.marketdata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.RedisPubSubAdapter;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Bridges display Pub/Sub to backend WebSockets and maintains renewable Alpaca subscription leases. */
public final class RedisDisplayPriceAdapter implements AutoCloseable {
    private static final Duration LEASE_TTL = Duration.ofSeconds(30);

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> commands;
    private final StatefulRedisPubSubConnection<String, String> pubSub;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String keyBase;
    private final Map<UUID, CopyOnWriteArrayList<Consumer<String>>> listeners = new ConcurrentHashMap<>();
    private final Map<String, String> activeLeases = new ConcurrentHashMap<>();
    private final ScheduledExecutorService leaseRefresher = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "market-display-lease-refresh");
        thread.setDaemon(true);
        return thread;
    });

    public static RedisDisplayPriceAdapter connect(String redisUri, String keyPrefix) {
        if (redisUri == null || redisUri.isBlank()) {
            throw new IllegalArgumentException("market-data.redis-uri must not be blank");
        }
        RedisClient client = RedisClient.create(redisUri);
        try {
            return new RedisDisplayPriceAdapter(client, keyPrefix);
        } catch (RuntimeException failure) {
            client.shutdown();
            throw failure;
        }
    }

    private RedisDisplayPriceAdapter(RedisClient client, String keyPrefix) {
        this.client = Objects.requireNonNull(client, "client");
        if (keyPrefix == null || keyPrefix.isBlank() || keyPrefix.contains("{") || keyPrefix.contains("}")) {
            throw new IllegalArgumentException("market-data.redis-key-prefix must be a plain non-empty value");
        }
        this.keyBase = "{" + keyPrefix + ":market}";
        this.commands = client.connect();
        this.pubSub = client.connectPubSub();
        this.pubSub.addListener(new RedisPubSubAdapter<>() {
            @Override
            public void message(String channel, String message) {
                if (!updatesChannel().equals(channel)) {
                    return;
                }
                try {
                    UUID instrumentId = UUID.fromString(mapper.readTree(message)
                            .path("instrumentId").asText());
                    listeners.getOrDefault(instrumentId, new CopyOnWriteArrayList<>())
                            .forEach(listener -> listener.accept(message));
                } catch (RuntimeException | java.io.IOException ignored) {
                    // One malformed display update must not kill the shared Redis subscription.
                }
            }
        });
        this.pubSub.sync().subscribe(updatesChannel());
        leaseRefresher.scheduleAtFixedRate(this::refreshLeases, 10, 10, TimeUnit.SECONDS);
    }

    public AutoCloseable subscribe(
            UUID instrumentId,
            String symbol,
            String connectionId,
            Consumer<String> listener) {
        Objects.requireNonNull(instrumentId, "instrumentId");
        Objects.requireNonNull(listener, "listener");
        String lease = requireText(connectionId, "connectionId") + "|" + requireText(symbol, "symbol");
        listeners.computeIfAbsent(instrumentId, ignored -> new CopyOnWriteArrayList<>()).add(listener);
        activeLeases.put(lease, symbol);
        refreshLease(lease);
        latest(instrumentId).ifPresent(listener);
        return () -> {
            CopyOnWriteArrayList<Consumer<String>> instrumentListeners = listeners.get(instrumentId);
            if (instrumentListeners != null) {
                instrumentListeners.remove(listener);
                if (instrumentListeners.isEmpty()) {
                    listeners.remove(instrumentId, instrumentListeners);
                }
            }
            activeLeases.remove(lease);
            commands.sync().zrem(leasesKey(), lease);
        };
    }

    public Optional<String> latest(UUID instrumentId) {
        return Optional.ofNullable(commands.sync().hget(keyBase + ":display:latest:" + instrumentId, "payload"));
    }

    private void refreshLeases() {
        activeLeases.keySet().forEach(this::refreshLease);
    }

    private void refreshLease(String lease) {
        commands.sync().zadd(leasesKey(), Instant.now().plus(LEASE_TTL).toEpochMilli(), lease);
    }

    private String updatesChannel() {
        return keyBase + ":display:price-updates";
    }

    private String leasesKey() {
        return keyBase + ":display:subscription-leases";
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    @Override
    public void close() {
        leaseRefresher.shutdownNow();
        activeLeases.keySet().forEach(lease -> commands.sync().zrem(leasesKey(), lease));
        pubSub.close();
        commands.close();
        client.shutdown();
    }
}
