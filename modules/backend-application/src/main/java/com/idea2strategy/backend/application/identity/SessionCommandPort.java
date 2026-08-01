package com.idea2strategy.backend.application.identity;

import java.time.Instant;
import java.util.UUID;

public interface SessionCommandPort {
    default void touch(UUID accountId, UUID sessionId, Instant now) {}

    default void recordEvent(
            UUID accountId,
            UUID loginIdentityId,
            UUID sessionId,
            String eventType,
            String reason,
            UUID correlationId,
            Instant now) {}

    boolean rotate(
            UUID accountId,
            UUID sessionId,
            String previousTokenDigest,
            String replacementTokenDigest,
            Instant expiresAt,
            UUID correlationId,
            Instant now);

    boolean revoke(UUID accountId, UUID sessionId, String reason, UUID correlationId, Instant now);

    int revokeAll(UUID accountId, String reason, UUID correlationId, Instant now);
}
