package com.idea2strategy.backend.application.identity;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Idempotent application boundary for expiring rotating refresh-token families. */
public interface RefreshTokenFamilyExpiryPort {
    record Identity(UUID accountId, UUID familyId, Instant expiresAt) {
        public Identity {
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(familyId, "familyId");
            Objects.requireNonNull(expiresAt, "expiresAt");
        }
    }

    enum Result {
        APPLIED,
        ALREADY_TRANSITIONED
    }

    List<Identity> findDueRefreshTokenFamilies(int limit);

    Result expire(Identity identity, UUID correlationId);
}
