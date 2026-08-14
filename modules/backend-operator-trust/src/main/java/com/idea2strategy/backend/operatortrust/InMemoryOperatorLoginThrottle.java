package com.idea2strategy.backend.operatortrust;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

final class InMemoryOperatorLoginThrottle implements OperatorLoginThrottle {
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration window;
    private final int loginLimit;
    private final int sourceLimit;

    InMemoryOperatorLoginThrottle(Clock clock, Duration window, int loginLimit, int sourceLimit) {
        this.clock = clock; this.window = window; this.loginLimit = loginLimit; this.sourceLimit = sourceLimit;
    }

    @Override public boolean acquire(String loginKey, String sourceKey) {
        long now = clock.millis();
        return increment("login:" + loginKey, now, loginLimit) && increment("source:" + sourceKey, now, sourceLimit);
    }

    @Override public void clearLogin(String loginKey) { windows.remove("login:" + loginKey); }

    private boolean increment(String key, long now, int limit) {
        Window value = windows.compute(key, (ignored, previous) ->
                previous == null || previous.expiresAt <= now
                        ? new Window(1, now + window.toMillis()) : new Window(previous.count + 1, previous.expiresAt));
        return value.count <= limit;
    }

    private record Window(int count, long expiresAt) {}
}
