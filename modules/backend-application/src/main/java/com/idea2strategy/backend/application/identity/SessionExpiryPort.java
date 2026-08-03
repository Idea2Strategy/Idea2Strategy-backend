package com.idea2strategy.backend.application.identity;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Idempotent application boundary for expiring server-owned sessions. */
public interface SessionExpiryPort {
    record Identity(UUID accountId, UUID sessionId, Instant expiresAt) {
        public Identity {
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    enum Result {
        APPLIED,
        ALREADY_TRANSITIONED
    }

    List<Identity> findDueSessions(int limit);

    Result expire(Identity identity, UUID correlationId);
}
