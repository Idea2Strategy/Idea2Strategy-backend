package com.idea2strategy.backend.api.botoperations;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idea2strategy.backend.application.botoperations.BotJudgmentLogEntry;
import com.idea2strategy.backend.application.botoperations.BotJudgmentLogSlice;
import com.idea2strategy.backend.application.botoperations.BotOperationsProjection;
import com.idea2strategy.backend.application.botoperations.BotOperationsInstrument;
import com.idea2strategy.backend.application.botoperations.BotOperationsQueryPort;
import com.idea2strategy.backend.application.botoperations.BotOperationsQueryService;
import com.idea2strategy.backend.domain.botcontrol.BotLifecycleStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class BotOperationsControllerTest {
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID BOT_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-01T12:00:00Z");

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        BotOperationsQueryPort port = new BotOperationsQueryPort() {
            @Override
            public List<BotOperationsProjection> findOwnedBots(UUID ownerAccountId) {
                return List.of(new BotOperationsProjection(
                        BOT_ID,
                        "Basic bot",
                        BotLifecycleStatus.RUNNING,
                        NOW,
                        NOW,
                        NOW.minusSeconds(10),
                        "MARKET_DATA_STALE",
                        8,
                        List.of(new BotOperationsInstrument(
                                UUID.fromString("70000000-0000-4000-8000-000000000001"), "AAPL"))));
            }

            @Override
            public Optional<BotJudgmentLogSlice> findOwnedJudgments(
                    UUID botId, UUID ownerAccountId, long afterSequence, int limit) {
                if (!BOT_ID.equals(botId) || !OWNER_ID.equals(ownerAccountId)) {
                    return Optional.empty();
                }
                var entry = new BotJudgmentLogEntry(
                        UUID.fromString("40000000-0000-4000-8000-000000000001"),
                        8,
                        "BOT_EVALUATED",
                        NOW,
                        Map.of("decision", "BUY"));
                return Optional.of(new BotJudgmentLogSlice(List.of(entry), false));
            }
        };
        var service = new BotOperationsQueryService(
                port, () -> OWNER_ID, Clock.fixed(NOW, ZoneOffset.UTC));
        mvc = MockMvcBuilders.standaloneSetup(new BotOperationsController(service))
                .setControllerAdvice(new BotOperationsExceptionHandler())
                .build();
    }

    @Test
    void exposesWireStateAndOrderedJudgments() throws Exception {
        mvc.perform(get("/api/v1/bots/operations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].botId").value(BOT_ID.toString()))
                .andExpect(jsonPath("$[0].state").value("data-degraded"))
                .andExpect(jsonPath("$[0].lastEventSequence").value(8));

        mvc.perform(get("/api/v1/bots/{botId}/judgments", BOT_ID)
                        .queryParam("afterSequence", "7")
                        .queryParam("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].sequence").value(8))
                .andExpect(jsonPath("$.entries[0].summary.decision").value("BUY"))
                .andExpect(jsonPath("$.nextAfterSequence").value(8))
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void returnsProblemResponsesForInvalidPageAndUnownedBot() throws Exception {
        mvc.perform(get("/api/v1/bots/{botId}/judgments", BOT_ID).queryParam("limit", "101"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/bots/{botId}/judgments", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }
}
