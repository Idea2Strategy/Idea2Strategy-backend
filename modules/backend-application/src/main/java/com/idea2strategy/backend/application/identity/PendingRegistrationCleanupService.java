package com.idea2strategy.backend.application.identity;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

public final class PendingRegistrationCleanupService {
    private final PendingRegistrationCleanupPort cleanup;
    private final Clock clock;
    private final Duration retention;

    public PendingRegistrationCleanupService(
            PendingRegistrationCleanupPort cleanup,
            Clock clock,
            Duration retention) {
        this.cleanup = Objects.requireNonNull(cleanup, "cleanup");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.retention = Objects.requireNonNull(retention, "retention");
        if (retention.isZero() || retention.isNegative()) {
            throw new IllegalArgumentException("retention must be positive");
        }
    }

    public int purgeExpired(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        var now = clock.instant();
        return cleanup.purgeExpired(now.minus(retention), now, limit);
    }
}
