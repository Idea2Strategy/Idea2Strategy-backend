package com.idea2strategy.backend.application.identity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record VerificationReplacement(
        UUID requestId,
        UUID accountId,
        String tokenDigest,
        Instant requestedAt,
        Instant expiresAt,
        UUID correlationId,
        String requestIpPrefix) {
    public VerificationReplacement {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(tokenDigest, "tokenDigest");
        Objects.requireNonNull(requestedAt, "requestedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(correlationId, "correlationId");
    }
}
