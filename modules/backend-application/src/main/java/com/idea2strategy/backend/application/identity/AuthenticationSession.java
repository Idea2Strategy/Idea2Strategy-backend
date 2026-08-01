package com.idea2strategy.backend.application.identity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AuthenticationSession(
        UUID id,
        UUID accountId,
        UUID loginIdentityId,
        long authEpoch,
        Long credentialVersion,
        String tokenDigest,
        String deviceLabel,
        Instant issuedAt,
        Instant expiresAt) {
    public AuthenticationSession {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(loginIdentityId, "loginIdentityId");
        Objects.requireNonNull(tokenDigest, "tokenDigest");
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
