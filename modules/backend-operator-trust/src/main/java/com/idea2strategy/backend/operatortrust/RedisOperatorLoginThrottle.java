package com.idea2strategy.backend.operatortrust;

import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;

public final class RedisOperatorLoginThrottle implements OperatorLoginThrottle, AutoCloseable {
    private static final String ACQUIRE = """
            local value = redis.call('INCR', KEYS[1])
            if value == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            if value > tonumber(ARGV[2]) then return 0 else return 1 end
            """;
    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final long windowMillis;
    private final int loginLimit;
    private final int sourceLimit;

    public RedisOperatorLoginThrottle(String redisUri, Duration window, int loginLimit, int sourceLimit) {
        if (redisUri == null || redisUri.isBlank()) throw new IllegalArgumentException("OPERATOR_THROTTLE_REDIS_URI_REQUIRED");
        this.client = RedisClient.create(redisUri);
        this.connection = client.connect();
        this.windowMillis = window.toMillis(); this.loginLimit = loginLimit; this.sourceLimit = sourceLimit;
    }

    @Override public boolean acquire(String loginKey, String sourceKey) {
        try {
            return increment("i2s:operator-auth:login:" + loginKey, loginLimit)
                    && increment("i2s:operator-auth:source:" + sourceKey, sourceLimit);
        } catch (RuntimeException unavailable) {
            throw new OperatorAuthenticationRejectedException("OPERATOR_AUTHENTICATION_UNAVAILABLE");
        }
    }

    @Override public void clearLogin(String loginKey) {
        try { connection.sync().del("i2s:operator-auth:login:" + loginKey); }
        catch (RuntimeException unavailable) { throw new OperatorAuthenticationRejectedException("OPERATOR_AUTHENTICATION_UNAVAILABLE"); }
    }

    private boolean increment(String key, int limit) {
        Long result = connection.sync().eval(ACQUIRE, ScriptOutputType.INTEGER,
                new String[] { key }, Long.toString(windowMillis), Integer.toString(limit));
        return result != null && result == 1L;
    }

    @Override public void close() { connection.close(); client.shutdown(); }
}
