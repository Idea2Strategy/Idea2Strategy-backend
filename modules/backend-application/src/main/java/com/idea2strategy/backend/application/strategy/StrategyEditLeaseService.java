package com.idea2strategy.backend.application.strategy;

import com.idea2strategy.backend.application.common.CurrentPrincipal;
import com.idea2strategy.backend.domain.strategy.StrategyEditLease;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.UUID;

public final class StrategyEditLeaseService {
    private final StrategyEditLeaseCommandPort commandPort;
    private final StrategyQueryPort strategyQueryPort;
    private final CurrentPrincipal principal;
    private final StrategyEditLeaseTokenGenerator tokenGenerator;
    private final Clock clock;
    private final Duration leaseDuration;

    public StrategyEditLeaseService(
            StrategyEditLeaseCommandPort commandPort,
            StrategyQueryPort strategyQueryPort,
            CurrentPrincipal principal,
            StrategyEditLeaseTokenGenerator tokenGenerator,
            Clock clock,
            Duration leaseDuration) {
        this.commandPort = Objects.requireNonNull(commandPort, "commandPort");
        this.strategyQueryPort = Objects.requireNonNull(strategyQueryPort, "strategyQueryPort");
        this.principal = Objects.requireNonNull(principal, "principal");
        this.tokenGenerator = Objects.requireNonNull(tokenGenerator, "tokenGenerator");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
    }

    public StrategyEditLeaseGrant acquire(UUID strategyId) {
        requireOwnedStrategy(strategyId);
        Instant now = clock.instant();
        String token = tokenGenerator.nextToken();
        Instant expiresAt = now.plus(leaseDuration);
        StrategyEditLease lease = new StrategyEditLease(
                strategyId,
                principal.accountId(),
                StrategyEditLeaseTokens.sha256(token),
                StrategyEditLeaseTokens.DIGEST_KEY_VERSION,
                now,
                now,
                expiresAt);
        if (!commandPort.acquire(lease, now)) {
            throw new StrategyEditLeaseUnavailableException();
        }
        return new StrategyEditLeaseGrant(token, expiresAt);
    }

    public Instant heartbeat(UUID strategyId, String token) {
        requireOwnedStrategy(strategyId);
        Instant now = clock.instant();
        Instant expiresAt = now.plus(leaseDuration);
        if (!commandPort.heartbeat(
                strategyId,
                principal.accountId(),
                StrategyEditLeaseTokens.sha256(token),
                now,
                expiresAt)) {
            throw new StrategyEditLeaseInvalidException();
        }
        return expiresAt;
    }

    public void release(UUID strategyId, String token) {
        requireOwnedStrategy(strategyId);
        if (!commandPort.release(
                strategyId, principal.accountId(), StrategyEditLeaseTokens.sha256(token))) {
            throw new StrategyEditLeaseInvalidException();
        }
    }

    private void requireOwnedStrategy(UUID strategyId) {
        strategyQueryPort
                .findOwnedById(strategyId, principal.accountId())
                .orElseThrow(() -> new NoSuchElementException("Strategy not found"));
    }
}
