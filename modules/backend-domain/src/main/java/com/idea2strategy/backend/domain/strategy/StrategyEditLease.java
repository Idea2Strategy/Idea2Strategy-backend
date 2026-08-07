package com.idea2strategy.backend.domain.strategy;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record StrategyEditLease(
        UUID strategyId,
        UUID accountId,
        String tokenDigest,
        short digestKeyVersion,
        Instant acquiredAt,
        Instant heartbeatAt,
        Instant expiresAt) {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public StrategyEditLease {
        Objects.requireNonNull(strategyId, "strategyId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(tokenDigest, "tokenDigest");
        Objects.requireNonNull(acquiredAt, "acquiredAt");
        Objects.requireNonNull(heartbeatAt, "heartbeatAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!SHA_256.matcher(tokenDigest).matches()) {
            throw new IllegalArgumentException("tokenDigest must be a lowercase SHA-256 digest");
        }
        if (digestKeyVersion <= 0) {
            throw new IllegalArgumentException("digestKeyVersion must be positive");
        }
        if (heartbeatAt.isBefore(acquiredAt) || !expiresAt.isAfter(heartbeatAt)) {
            throw new IllegalArgumentException("Lease timestamps are out of order");
        }
    }

    public StrategyEditLease heartbeat(Instant nextHeartbeatAt, Instant nextExpiresAt) {
        return new StrategyEditLease(
                strategyId,
                accountId,
                tokenDigest,
                digestKeyVersion,
                acquiredAt,
                nextHeartbeatAt,
                nextExpiresAt);
    }
}
