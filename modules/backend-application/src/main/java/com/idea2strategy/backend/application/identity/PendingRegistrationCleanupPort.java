package com.idea2strategy.backend.application.identity;

import java.time.Instant;

@FunctionalInterface
public interface PendingRegistrationCleanupPort {
    int purgeExpired(Instant cutoff, Instant purgedAt, int limit);
}
