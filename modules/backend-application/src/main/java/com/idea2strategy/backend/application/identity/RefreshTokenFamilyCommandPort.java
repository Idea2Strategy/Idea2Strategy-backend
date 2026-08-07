package com.idea2strategy.backend.application.identity;

import java.time.Instant;
import java.util.UUID;

public interface RefreshTokenFamilyCommandPort {
    default void touch(UUID accountId, UUID familyId, Instant now) {}

    default void recordEvent(
            UUID accountId,
            UUID loginIdentityId,
            UUID familyId,
            String eventType,
            String reason,
            UUID correlationId,
            Instant now) {}

    boolean rotate(
            UUID accountId,
            UUID familyId,
            String previousTokenDigest,
            String replacementTokenDigest,
            Instant expiresAt,
            UUID correlationId,
            Instant now);

    boolean revoke(UUID accountId, UUID familyId, String reason, UUID correlationId, Instant now);

    int revokeAll(UUID accountId, String reason, UUID correlationId, Instant now);
}
