package com.idea2strategy.backend.messaging.marketdata;

import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** A short-lived, cluster-shared, consume-once ticket store. */
public final class RedisSingleUseTicketStore implements AutoCloseable {
    private static final String TAKE_SCRIPT = """
            local value = redis.call('GET', KEYS[1])
            if value == false then return '' end
            redis.call('DEL', KEYS[1])
            return value
            """;

    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final String keyBase;

    public static RedisSingleUseTicketStore connect(String redisUri, String keyPrefix) {
        if (redisUri == null || redisUri.isBlank()) {
            throw new IllegalArgumentException("market-data.redis-uri must not be blank");
        }
        RedisClient client = RedisClient.create(redisUri);
        try {
            return new RedisSingleUseTicketStore(client, keyPrefix);
        } catch (RuntimeException failure) {
            client.shutdown();
            throw failure;
        }
    }

    RedisSingleUseTicketStore(RedisClient client, String keyPrefix) {
        this.client = Objects.requireNonNull(client, "client");
        if (keyPrefix == null || keyPrefix.isBlank() || keyPrefix.contains("{") || keyPrefix.contains("}")) {
            throw new IllegalArgumentException("market-data.redis-key-prefix must be a plain non-empty value");
        }
        this.keyBase = "{" + keyPrefix + ":market}:websocket-ticket:";
        this.connection = client.connect();
    }

    public void put(String ticket, String subject, Duration lifetime) {
        requireText(ticket, "ticket");
        requireText(subject, "subject");
        Objects.requireNonNull(lifetime, "lifetime");
        if (lifetime.isZero() || lifetime.isNegative()) {
            throw new IllegalArgumentException("lifetime must be positive");
        }
        String stored = connection.sync().set(
                key(ticket), subject, SetArgs.Builder.nx().px(lifetime.toMillis()));
        if (stored == null) {
            throw new IllegalStateException("websocket ticket collision");
        }
    }

    public Optional<String> take(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return Optional.empty();
        }
        Object value = connection.sync().eval(
                TAKE_SCRIPT, ScriptOutputType.VALUE, new String[] {key(ticket)});
        String subject = value == null ? "" : value.toString();
        return subject.isBlank() ? Optional.empty() : Optional.of(subject);
    }

    private String key(String ticket) {
        return keyBase + ticket;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    @Override
    public void close() {
        connection.close();
        client.shutdown();
    }
}
