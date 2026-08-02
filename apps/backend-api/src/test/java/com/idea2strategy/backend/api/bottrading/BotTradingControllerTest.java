package com.idea2strategy.backend.api.bottrading;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idea2strategy.backend.application.bottrading.BotBudgetView;
import com.idea2strategy.backend.application.bottrading.BotDecisionReasonView;
import com.idea2strategy.backend.application.bottrading.BotFillView;
import com.idea2strategy.backend.application.bottrading.BotOrderView;
import com.idea2strategy.backend.application.bottrading.BotPositionView;
import com.idea2strategy.backend.application.bottrading.BotStopSettlementView;
import com.idea2strategy.backend.application.bottrading.BotTradingQueryPort;
import com.idea2strategy.backend.application.bottrading.BotTradingQueryService;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;

class BotTradingControllerTest {
    private static final UUID OWNER = UUID.fromString("10000000-0000-4000-8000-00000000000a");
    private static final UUID BOT = UUID.fromString("30000000-0000-4000-8000-00000000000a");
    private static final UUID ORDER = UUID.fromString("39000000-0000-4000-8000-00000000000a");
    private static final Instant AT = Instant.parse("2026-08-03T12:00:00Z");

    private MockMvc mvc;
    private int lastLimit;

    @BeforeEach
    void setUp() {
        BotTradingQueryPort port = new BotTradingQueryPort() {
            @Override
            public Optional<List<BotOrderView>> findOwnedOrders(UUID botId, UUID owner, int limit) {
                lastLimit = limit;
                return Optional.of(List.of(new BotOrderView(
                        ORDER, null, null, "BUY", "MARKET", "DAY", new BigDecimal("3"),
                        new BigDecimal("3"), BigDecimal.ZERO, "FILLED", AT)));
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
                        "USD", new BigDecimal("969.94"), BigDecimal.ZERO, new BigDecimal("30.06"),
                        AT, "VALUED", 4L, List.of()));
            }

            @Override
            public Optional<List<BotDecisionReasonView>> findOwnedDecisionReasons(
                    UUID botId, UUID owner, int limit) {
                return Optional.of(List.of());
            }

            @Override
            public Optional<List<BotStopSettlementView>> findOwnedStopSettlement(
                    UUID botId, UUID owner) {
                return Optional.of(List.of());
            }
        };
        mvc = MockMvcBuilders
                .standaloneSetup(new BotTradingController(
                        new BotTradingQueryService(port, () -> OWNER)))
                .build();
    }

    @Test
    void ordersAreServedUnderTheBot() throws Exception {
        mvc.perform(get("/api/v1/bots/{botId}/orders", BOT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].orderId").value(ORDER.toString()))
                .andExpect(jsonPath("$[0].status").value("FILLED"));
    }

    @Test
    void theBudgetIsServedAsOneObjectRatherThanAList() throws Exception {
        mvc.perform(get("/api/v1/bots/{botId}/budget", BOT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currencyCode").value("USD"))
                .andExpect(jsonPath("$.availableCashAmount").value(969.94));
    }

    @Test
    void anAbsentLimitFallsBackToTheDefaultPageSize() throws Exception {
        mvc.perform(get("/api/v1/bots/{botId}/orders", BOT)).andExpect(status().isOk());

        assertThat(lastLimit).isEqualTo(50);
    }

    /**
     * {@code policy.user.no-direct-orders} is approved and says a user cannot submit an order or an
     * order intention outside their locked strategy. A read surface that quietly grew a write method
     * would break that policy without anything else noticing, so the shape of the controller itself
     * is asserted rather than trusted.
     */
    @Test
    void theControllerOffersNoWayToSubmitAnOrder() throws Exception {
        for (Method method : BotTradingController.class.getDeclaredMethods()) {
            if (method.isSynthetic()) {
                continue;
            }
            assertThat(method.isAnnotationPresent(GetMapping.class))
                    .as("%s must be a read", method.getName())
                    .isTrue();
        }

        mvc.perform(post("/api/v1/bots/{botId}/orders", BOT))
                .andExpect(status().isMethodNotAllowed());
    }
}
