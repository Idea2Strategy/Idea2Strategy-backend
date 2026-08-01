package com.idea2strategy.backend.application.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.application.testing.MutableClock;
import com.idea2strategy.backend.application.testing.TestSessionPrincipal;
import com.idea2strategy.backend.domain.strategy.Strategy;
import com.idea2strategy.backend.domain.strategy.StrategyEditLease;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StrategyEditLeaseServiceTest {
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID STRATEGY_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID SESSION_ONE = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID SESSION_TWO = UUID.fromString("30000000-0000-4000-8000-000000000002");
    private static final Instant NOW = Instant.parse("2026-08-01T04:00:00Z");
    private static final Duration LEASE_DURATION = Duration.ofMinutes(5);

    @Test
    void blocksAnotherSessionUntilReleaseAndThenAllowsIt() {
        var repository = new InMemoryLeaseRepository();
        var clock = new MutableClock(NOW, ZoneOffset.UTC);
        var first = service(repository, clock, SESSION_ONE, "lease-token-one");
        var second = service(repository, clock, SESSION_TWO, "lease-token-two");

        StrategyEditLeaseGrant firstGrant = first.acquire(STRATEGY_ID);

        assertThat(firstGrant.token()).isEqualTo("lease-token-one");
        assertThat(firstGrant.expiresAt()).isEqualTo(NOW.plus(LEASE_DURATION));
        assertThat(repository.lease.tokenDigest())
                .isEqualTo(StrategyEditLeaseTokens.sha256("lease-token-one"));
        assertThatThrownBy(() -> second.acquire(STRATEGY_ID))
                .isInstanceOf(StrategyEditLeaseUnavailableException.class)
                .hasMessage("Strategy is already being edited");

        first.release(STRATEGY_ID, firstGrant.token());
        assertThat(second.acquire(STRATEGY_ID).token()).isEqualTo("lease-token-two");
    }

    @Test
    void heartbeatExtendsTheLeaseAndAnExpiredLeaseCanBeRecoveredByAnotherSession() {
        var repository = new InMemoryLeaseRepository();
        var clock = new MutableClock(NOW, ZoneOffset.UTC);
        var first = service(repository, clock, SESSION_ONE, "lease-token-one");
        var second = service(repository, clock, SESSION_TWO, "lease-token-two");
        StrategyEditLeaseGrant grant = first.acquire(STRATEGY_ID);

        clock.advanceTo(NOW.plus(Duration.ofMinutes(2)));
        Instant extendedTo = first.heartbeat(STRATEGY_ID, grant.token());

        assertThat(extendedTo).isEqualTo(NOW.plus(Duration.ofMinutes(7)));
        clock.advanceTo(extendedTo.plusMillis(1));
        assertThat(second.acquire(STRATEGY_ID).token()).isEqualTo("lease-token-two");
    }

    @Test
    void wrongOrExpiredTokenCannotHeartbeatOrRelease() {
        var repository = new InMemoryLeaseRepository();
        var clock = new MutableClock(NOW, ZoneOffset.UTC);
        var service = service(repository, clock, SESSION_ONE, "lease-token-one");
        service.acquire(STRATEGY_ID);

        assertThatThrownBy(() -> service.heartbeat(STRATEGY_ID, "wrong-token"))
                .isInstanceOf(StrategyEditLeaseInvalidException.class);
        assertThatThrownBy(() -> service.release(STRATEGY_ID, "wrong-token"))
                .isInstanceOf(StrategyEditLeaseInvalidException.class);

        clock.advanceTo(NOW.plus(LEASE_DURATION).plusMillis(1));
        assertThatThrownBy(() -> service.heartbeat(STRATEGY_ID, "lease-token-one"))
                .isInstanceOf(StrategyEditLeaseInvalidException.class);
    }

    private static StrategyEditLeaseService service(
            InMemoryLeaseRepository repository, MutableClock clock, UUID sessionId, String token) {
        return new StrategyEditLeaseService(
                repository,
                repository,
                new TestSessionPrincipal(OWNER_ID, sessionId),
                () -> token,
                clock,
                LEASE_DURATION);
    }

    private static final class InMemoryLeaseRepository
            implements StrategyEditLeaseCommandPort, StrategyQueryPort {
        private final Strategy strategy = Strategy.createBasic(STRATEGY_ID, OWNER_ID, "Momentum", null, NOW);
        private StrategyEditLease lease;

        @Override
        public boolean acquire(StrategyEditLease candidate, Instant now) {
            if (lease != null && lease.expiresAt().isAfter(now)) {
                return false;
            }
            lease = candidate;
            return true;
        }

        @Override
        public boolean heartbeat(
                UUID strategyId,
                UUID sessionId,
                String tokenDigest,
                Instant heartbeatAt,
                Instant expiresAt) {
            if (!matches(strategyId, sessionId, tokenDigest) || !lease.expiresAt().isAfter(heartbeatAt)) {
                return false;
            }
            lease = lease.heartbeat(heartbeatAt, expiresAt);
            return true;
        }

        @Override
        public boolean release(UUID strategyId, UUID sessionId, String tokenDigest) {
            if (!matches(strategyId, sessionId, tokenDigest)) {
                return false;
            }
            lease = null;
            return true;
        }

        @Override
        public Optional<Strategy> findOwnedById(UUID strategyId, UUID ownerAccountId) {
            return strategy.id().equals(strategyId) && strategy.ownerAccountId().equals(ownerAccountId)
                    ? Optional.of(strategy)
                    : Optional.empty();
        }

        private boolean matches(UUID strategyId, UUID sessionId, String tokenDigest) {
            return lease != null
                    && lease.strategyId().equals(strategyId)
                    && lease.sessionId().equals(sessionId)
                    && lease.tokenDigest().equals(tokenDigest);
        }
    }
}
