package com.idea2strategy.backend.application.identity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PendingRegistrationReplacement(
        UUID requestId,
        UUID accountId,
        PasswordHash password,
        String tokenDigest,
        Instant requestedAt,
        Instant expiresAt,
        UUID correlationId,
        String requestIpPrefix) {
    public PendingRegistrationReplacement {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(tokenDigest, "tokenDigest");
        Objects.requireNonNull(requestedAt, "requestedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(correlationId, "correlationId");
    }

    @Override
    public String toString() {
        return "PendingRegistrationReplacement[accountId=" + accountId + ", credentials=REDACTED]";
    }
}
