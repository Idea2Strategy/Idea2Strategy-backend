package com.idea2strategy.backend.application.identity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RefreshTokenFamily(
        UUID id,
        UUID accountId,
        UUID loginIdentityId,
        long authEpoch,
        Long credentialVersion,
        String tokenDigest,
        Instant issuedAt,
        Instant expiresAt) {
    public RefreshTokenFamily {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(loginIdentityId, "loginIdentityId");
        Objects.requireNonNull(tokenDigest, "tokenDigest");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
