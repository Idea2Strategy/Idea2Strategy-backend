package com.idea2strategy.backend.application.botcontrol;

import static org.assertj.core.api.Assertions.assertThat;

import com.idea2strategy.backend.application.testing.FixedIdGenerator;
import com.idea2strategy.backend.application.testing.MutableClock;
import com.idea2strategy.backend.application.testing.RecordingDomainEventPublisher;
import com.idea2strategy.backend.application.testing.TestPrincipal;
import com.idea2strategy.backend.domain.botcontrol.Bot;
import com.idea2strategy.backend.domain.botcontrol.BotLifecycleStatus;
import com.idea2strategy.backend.domain.botcontrol.BotStarted;
import com.idea2strategy.backend.domain.botcontrol.BotStopRequested;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BotCommandQueryServiceTest {
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID BOT_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final Instant STARTED_AT = Instant.parse("2026-08-01T00:00:00Z");
    private static final Instant STOP_REQUESTED_AT = Instant.parse("2026-08-01T01:00:00Z");

    @Test
    void startsAndRequestsStopWithDeterministicEvents() {
        var repository = new InMemoryBotRepository();
        var principal = new TestPrincipal(OWNER_ID);
        var clock = new MutableClock(STARTED_AT, ZoneOffset.UTC);
        var events = new RecordingDomainEventPublisher();
        var commands = new BotCommandService(
                repository,
                repository,
                principal,
                new FixedIdGenerator(BOT_ID),
                clock,
                events);
        var queries = new BotQueryService(repository, principal);

        UUID id = commands.startBasic("Basic bot");
        clock.advanceTo(STOP_REQUESTED_AT);
        commands.requestStop(id, "USER_REQUESTED");
        Bot loaded = queries.getOwned(id);

        assertThat(loaded.lifecycleStatus()).isEqualTo(BotLifecycleStatus.STOPPING);
        assertThat(loaded.stopRequestedAt()).isEqualTo(STOP_REQUESTED_AT);
        assertThat(events.publishedEvents()).containsExactly(
                new BotStarted(BOT_ID, OWNER_ID, STARTED_AT),
                new BotStopRequested(BOT_ID, OWNER_ID, "USER_REQUESTED", STOP_REQUESTED_AT));
    }

    private static final class InMemoryBotRepository implements BotCommandPort, BotQueryPort {
        private final Map<UUID, Bot> bots = new HashMap<>();

        @Override
        public void save(Bot bot) {
            bots.put(bot.id(), bot);
        }

        @Override
        public Optional<Bot> findOwnedById(UUID botId, UUID ownerAccountId) {
            return Optional.ofNullable(bots.get(botId))
                    .filter(bot -> bot.ownerAccountId().equals(ownerAccountId));
        }
    }
}
