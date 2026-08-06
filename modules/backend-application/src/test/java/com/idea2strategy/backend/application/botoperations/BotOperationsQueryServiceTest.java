package com.idea2strategy.backend.application.botoperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.application.testing.TestPrincipal;
import com.idea2strategy.backend.domain.botcontrol.BotLifecycleStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BotOperationsQueryServiceTest {
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID BOT_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID EVENT_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-01T12:00:00Z");

    @Test
    void projectsApprovedUiStatesWithoutTreatingUnknownBlocksAsHealthy() {
        var port = new RecordingPort(List.of(
                projection(BOT_ID, BotLifecycleStatus.RUNNING, NOW.plusSeconds(60), null, null),
                projection(UUID.randomUUID(), BotLifecycleStatus.RUNNING, NOW, null, null),
                projection(UUID.randomUUID(), BotLifecycleStatus.RUNNING, NOW, NOW.minusSeconds(5), "UNRECOVERABLE_STATE"),
                projection(UUID.randomUUID(), BotLifecycleStatus.RUNNING, NOW, NOW.minusSeconds(5), "MARKET_DATA_STALE"),
                projection(UUID.randomUUID(), BotLifecycleStatus.RUNNING, NOW, NOW.minusSeconds(5), "SETTLEMENT_FAILED"),
                projection(UUID.randomUUID(), BotLifecycleStatus.STOPPING, NOW, null, null),
                projection(UUID.randomUUID(), BotLifecycleStatus.STOPPED, NOW, null, null)));
        var service = service(port);

        assertThat(service.listOwned().stream().map(BotOperationsView::state))
                .containsExactly(
                        BotOperationsState.WAITING,
                        BotOperationsState.RUNNING,
                        BotOperationsState.ACTION_REQUIRED,
                        BotOperationsState.DATA_DEGRADED,
                        BotOperationsState.SETTLEMENT_FAILED,
                        BotOperationsState.STOPPING,
                        BotOperationsState.STOPPED);
        assertThat(port.requestedOwner).isEqualTo(OWNER_ID);
    }

    @Test
    void returnsOwnedJudgmentsAfterCursorInSequenceOrder() {
        var summary = Map.<String, Object>of("decision", "BUY");
        var event = new BotJudgmentLogEntry(EVENT_ID, 8L, "BOT_EVALUATED", NOW, summary);
        var port = new RecordingPort(List.of(projection(BOT_ID, BotLifecycleStatus.RUNNING, NOW, null, null)));
        port.slice = Optional.of(new BotJudgmentLogSlice(List.of(event), false));

        var page = service(port).getJudgments(BOT_ID, 7L, 20);

        assertThat(page.entries()).containsExactly(event);
        assertThat(page.nextAfterSequence()).isEqualTo(8L);
        assertThat(page.hasMore()).isFalse();
        assertThat(port.requestedOwner).isEqualTo(OWNER_ID);
        assertThat(port.requestedBot).isEqualTo(BOT_ID);
        assertThat(port.requestedAfter).isEqualTo(7L);
        assertThat(port.requestedLimit).isEqualTo(20);
    }

    @Test
    void rejectsMissingOrUnownedBotAndUnsafeLimits() {
        var service = service(new RecordingPort(List.of()));

        assertThatThrownBy(() -> service.getJudgments(BOT_ID, 0, 20))
                .isInstanceOf(BotOperationsNotFoundException.class);
        assertThatThrownBy(() -> service.getJudgments(BOT_ID, 0, 101))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static BotOperationsQueryService service(BotOperationsQueryPort port) {
        return new BotOperationsQueryService(
                port, new TestPrincipal(OWNER_ID), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static BotOperationsProjection projection(
            UUID id,
            BotLifecycleStatus lifecycle,
            Instant eligibleFrom,
            Instant blockedAt,
            String blockReason) {
        return new BotOperationsProjection(
                id, "Basic bot", lifecycle, NOW.minusSeconds(60), eligibleFrom, blockedAt, blockReason, 7L,
                List.of(new BotOperationsInstrument(UUID.fromString("70000000-0000-4000-8000-000000000001"), "AAPL")));
    }

    private static final class RecordingPort implements BotOperationsQueryPort {
        private final List<BotOperationsProjection> projections;
        private Optional<BotJudgmentLogSlice> slice = Optional.empty();
        private UUID requestedOwner;
        private UUID requestedBot;
        private long requestedAfter;
        private int requestedLimit;

        private RecordingPort(List<BotOperationsProjection> projections) {
            this.projections = projections;
        }

        @Override
        public List<BotOperationsProjection> findOwnedBots(UUID ownerAccountId) {
            requestedOwner = ownerAccountId;
            return projections;
        }

        @Override
        public Optional<BotJudgmentLogSlice> findOwnedJudgments(
                UUID botId, UUID ownerAccountId, long afterSequence, int limit) {
            requestedBot = botId;
            requestedOwner = ownerAccountId;
            requestedAfter = afterSequence;
            requestedLimit = limit;
            return slice;
        }
    }
}
