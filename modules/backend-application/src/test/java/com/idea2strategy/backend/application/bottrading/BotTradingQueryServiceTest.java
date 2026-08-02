package com.idea2strategy.backend.application.bottrading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idea2strategy.backend.application.botoperations.BotOperationsNotFoundException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BotTradingQueryServiceTest {
    private static final UUID OWNER = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID BOT = UUID.fromString("30000000-0000-4000-8000-000000000001");

    /**
     * The port reports "not the caller's bot" as an absent Optional, and the service has to turn
     * that into the same not-found the rest of the bot API returns. Anything else would let a
     * caller tell a bot they do not own apart from one that does not exist.
     */
    @Test
    void anUnownedBotIsNotFoundRatherThanEmpty() {
        BotTradingQueryService service = new BotTradingQueryService(new RefusingPort(), () -> OWNER);

        assertThatThrownBy(() -> service.listOrders(BOT, null))
                .isInstanceOf(BotOperationsNotFoundException.class);
        assertThatThrownBy(() -> service.getBudget(BOT))
                .isInstanceOf(BotOperationsNotFoundException.class);
        assertThatThrownBy(() -> service.listStopSettlement(BOT))
                .isInstanceOf(BotOperationsNotFoundException.class);
    }

    /** An owned bot that has not traded answers with nothing, which is not the same as not found. */
    @Test
    void anOwnedBotWithNoTradingYetAnswersEmpty() {
        BotTradingQueryService service = new BotTradingQueryService(new EmptyPort(), () -> OWNER);

        assertThat(service.listOrders(BOT, null)).isEmpty();
        assertThat(service.listFills(BOT, null)).isEmpty();
        assertThat(service.listPositions(BOT)).isEmpty();
    }

    @Test
    void theOwnerOfTheRequestIsTheOneTheQueryIsScopedTo() {
        RecordingPort port = new RecordingPort();
        new BotTradingQueryService(port, () -> OWNER).listFills(BOT, 10);

        assertThat(port.lastOwner).isEqualTo(OWNER);
        assertThat(port.lastLimit).isEqualTo(10);
    }

    @Test
    void aLimitOutsideTheAllowedRangeIsRefused() {
        BotTradingQueryService service = new BotTradingQueryService(new EmptyPort(), () -> OWNER);

        assertThatThrownBy(() -> service.listOrders(BOT, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.listOrders(BOT, 201))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(service.listOrders(BOT, 200)).isEmpty();
    }

    private static class EmptyPort implements BotTradingQueryPort {
        @Override
        public Optional<List<BotOrderView>> findOwnedOrders(UUID botId, UUID owner, int limit) {
            return Optional.of(List.of());
        }

        @Override
        public Optional<List<BotFillView>> findOwnedFills(UUID botId, UUID owner, int limit) {
            return Optional.of(List.of());
        }

        @Override
        public Optional<List<BotPositionView>> findOwnedPositions(UUID botId, UUID owner) {
            return Optional.of(List.of());
        }

        @Override
        public Optional<BotBudgetView> findOwnedBudget(UUID botId, UUID owner) {
            return Optional.of(new BotBudgetView(
                    null, null, null, null, null, "UNVALUED", 0L, List.of()));
        }

        @Override
        public Optional<List<BotDecisionReasonView>> findOwnedDecisionReasons(
                UUID botId, UUID owner, int limit) {
            return Optional.of(List.of());
        }

        @Override
        public Optional<List<BotStopSettlementView>> findOwnedStopSettlement(UUID botId, UUID owner) {
            return Optional.of(List.of());
        }
    }

    private static final class RefusingPort extends EmptyPort {
        @Override
        public Optional<List<BotOrderView>> findOwnedOrders(UUID botId, UUID owner, int limit) {
            return Optional.empty();
        }

        @Override
        public Optional<BotBudgetView> findOwnedBudget(UUID botId, UUID owner) {
            return Optional.empty();
        }

        @Override
        public Optional<List<BotStopSettlementView>> findOwnedStopSettlement(UUID botId, UUID owner) {
            return Optional.empty();
        }
    }

    private static final class RecordingPort extends EmptyPort {
        private UUID lastOwner;
        private int lastLimit;

        @Override
        public Optional<List<BotFillView>> findOwnedFills(UUID botId, UUID owner, int limit) {
            this.lastOwner = owner;
            this.lastLimit = limit;
            return Optional.of(List.of());
        }
    }
}
