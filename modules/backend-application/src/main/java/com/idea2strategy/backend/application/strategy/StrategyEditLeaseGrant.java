package com.idea2strategy.backend.application.strategy;

import java.time.Instant;
import java.util.Objects;

public record StrategyEditLeaseGrant(String token, Instant expiresAt) {
    public StrategyEditLeaseGrant {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (token.isBlank()) {
            throw new IllegalArgumentException("token must not be blank");
        }
    }
}
