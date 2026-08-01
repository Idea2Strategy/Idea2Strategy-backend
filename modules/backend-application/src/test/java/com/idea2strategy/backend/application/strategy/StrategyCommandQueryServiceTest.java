package com.idea2strategy.backend.application.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.application.testing.FixedIdGenerator;
import com.idea2strategy.backend.application.testing.RecordingDomainEventPublisher;
import com.idea2strategy.backend.application.testing.TestPrincipal;
import com.idea2strategy.backend.domain.strategy.Strategy;
import com.idea2strategy.backend.domain.strategy.StrategyCreated;
import com.idea2strategy.backend.domain.strategy.StrategyMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StrategyCommandQueryServiceTest {
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID OTHER_OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000002");
    private static final UUID STRATEGY_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");

    @Test
    void createsAndReadsOnlyTheOwnersBasicStrategy() {
        var repository = new InMemoryStrategyRepository();
        var events = new RecordingDomainEventPublisher();
        var owner = new TestPrincipal(OWNER_ID);
        var commands = new StrategyCommandService(
                repository,
                owner,
                new FixedIdGenerator(STRATEGY_ID),
                Clock.fixed(NOW, ZoneOffset.UTC),
                events);

        UUID id = commands.createBasic("Momentum", "Minimal strategy");
        Strategy loaded = new StrategyQueryService(repository, owner).getOwned(id);

        assertThat(loaded.id()).isEqualTo(STRATEGY_ID);
        assertThat(loaded.mode()).isEqualTo(StrategyMode.BASIC);
        assertThat(events.publishedEvents()).containsExactly(
                new StrategyCreated(STRATEGY_ID, OWNER_ID, StrategyMode.BASIC, NOW));
        assertThatThrownBy(() -> new StrategyQueryService(repository, new TestPrincipal(OTHER_OWNER_ID))
                        .getOwned(id))
                .isInstanceOf(NoSuchElementException.class);
    }

    private static final class InMemoryStrategyRepository implements StrategyCommandPort, StrategyQueryPort {
        private final Map<UUID, Strategy> strategies = new HashMap<>();

        @Override
        public void save(Strategy strategy) {
            strategies.put(strategy.id(), strategy);
        }

        @Override
        public Optional<Strategy> findOwnedById(UUID strategyId, UUID ownerAccountId) {
            return Optional.ofNullable(strategies.get(strategyId))
                    .filter(strategy -> strategy.ownerAccountId().equals(ownerAccountId));
        }
    }
}
