package com.idea2strategy.backend.application.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.idea2strategy.backend.application.botoperations.BotOperationsState;
import com.idea2strategy.backend.application.testing.TestPrincipal;
import com.idea2strategy.backend.domain.botcontrol.BotLifecycleStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DashboardQueryServiceTest {
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID BOT_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID ROOM_ID = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-07T12:00:00Z");

    @Test
    void returnsOwnedBotsWithTruthfulPerformanceAndCompetitionContext() {
        var projection = new DashboardBotProjection(
                BOT_ID,
                "Confirmed bot",
                BotLifecycleStatus.RUNNING,
                NOW.minusSeconds(120),
                NOW.minusSeconds(60),
                null,
                null,
                new DashboardPerformanceProjection(
                        new BigDecimal("10540.00"),
                        new BigDecimal("5.40"),
                        new BigDecimal("-2.10"),
                        new BigDecimal("1.25"),
                        "performance-v1",
                        NOW.minusSeconds(30)),
                new DashboardCompetitionProjection(
                        ROOM_ID,
                        "Momentum Lab",
                        "EVALUATING",
                        "EVALUATING",
                        NOW.plusSeconds(86400),
                        "Asia/Seoul"));
        var port = new RecordingPort(List.of(projection));
        var service = new DashboardQueryService(
                port,
                new TestPrincipal(OWNER_ID),
                Clock.fixed(NOW, ZoneOffset.UTC));

        var snapshot = service.getOwnedSnapshot();

        assertThat(port.requestedOwner).isEqualTo(OWNER_ID);
        assertThat(snapshot.generatedAt()).isEqualTo(NOW);
        assertThat(snapshot.bots()).singleElement().satisfies(bot -> {
            assertThat(bot.botId()).isEqualTo(BOT_ID);
            assertThat(bot.state()).isEqualTo(BotOperationsState.RUNNING);
            assertThat(bot.performance()).isNotNull();
            assertThat(bot.performance().equityAmount()).isEqualByComparingTo("10540.00");
            assertThat(bot.competition()).isNotNull();
            assertThat(bot.competition().roomId()).isEqualTo(ROOM_ID);
        });
    }

    @Test
    void keepsMissingPerformanceAndCompetitionDistinctFromAnEmptyAccount() {
        var botWithoutProjections = new DashboardBotProjection(
                BOT_ID,
                "New bot",
                BotLifecycleStatus.RUNNING,
                NOW,
                NOW.plusSeconds(60),
                null,
                null,
                null,
                null);
        var service = new DashboardQueryService(
                new RecordingPort(List.of(botWithoutProjections)),
                new TestPrincipal(OWNER_ID),
                Clock.fixed(NOW, ZoneOffset.UTC));

        var snapshot = service.getOwnedSnapshot();

        assertThat(snapshot.bots()).hasSize(1);
        assertThat(snapshot.bots().getFirst().state()).isEqualTo(BotOperationsState.WAITING);
        assertThat(snapshot.bots().getFirst().performance()).isNull();
        assertThat(snapshot.bots().getFirst().competition()).isNull();
        assertThat(new DashboardQueryService(
                        new RecordingPort(List.of()),
                        new TestPrincipal(OWNER_ID),
                        Clock.fixed(NOW, ZoneOffset.UTC))
                .getOwnedSnapshot().bots())
                .isEmpty();
    }

    private static final class RecordingPort implements DashboardQueryPort {
        private final List<DashboardBotProjection> projections;
        private UUID requestedOwner;

        private RecordingPort(List<DashboardBotProjection> projections) {
            this.projections = projections;
        }

        @Override
        public List<DashboardBotProjection> findOwned(UUID ownerAccountId) {
            requestedOwner = ownerAccountId;
            return projections;
        }
    }
}
