package com.idea2strategy.backend.application.identity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record StoredOidcStepUpChallenge(
        UUID id,
        short providerId,
        String providerCode,
        String nonceDigest,
        Instant expiresAt,
        Instant consumedAt,
        UUID consumedByAccountId) {
    public StoredOidcStepUpChallenge {
        Objects.requireNonNull(id, "id");
        if (providerId < 1 || Objects.requireNonNull(providerCode, "providerCode").isBlank()
                || Objects.requireNonNull(nonceDigest, "nonceDigest").isBlank()) {
            throw new IllegalArgumentException("Stored OIDC challenge must be complete");
        }
        Objects.requireNonNull(expiresAt, "expiresAt");
        if ((consumedAt == null) != (consumedByAccountId == null)) {
            throw new IllegalArgumentException("OIDC challenge consumption fields must be paired");
        }
    }

    public boolean activeAt(Instant now) {
        return consumedAt == null && now.isBefore(expiresAt);
    }

    public boolean unexpiredAt(Instant now) {
        return now.isBefore(expiresAt);
    }
}
