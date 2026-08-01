package com.idea2strategy.backend.api.botcontrol;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idea2strategy.backend.application.botcontrol.BotExecutionPreflightFacts;
import com.idea2strategy.backend.application.botcontrol.BotExecutionPreflightQueryPort;
import com.idea2strategy.backend.application.botcontrol.BotExecutionPreflightService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class BotExecutionPreflightControllerTest {
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID BOT_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID INSTRUMENT_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-01T09:00:00Z");

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        BotExecutionPreflightQueryPort port = (botId, ownerId, at) -> {
            if (!BOT_ID.equals(botId) || !OWNER_ID.equals(ownerId)) {
                return Optional.empty();
            }
            return Optional.of(new BotExecutionPreflightFacts(
                    BOT_ID,
                    new BigDecimal("100000.00"),
                    11,
                    List.of(INSTRUMENT_ID),
                    true,
                    true,
                    true,
                    List.of()));
        };
        var service = new BotExecutionPreflightService(
                port, () -> OWNER_ID, Clock.fixed(NOW, ZoneOffset.UTC));
        mvc = MockMvcBuilders.standaloneSetup(new BotExecutionPreflightController(service))
                .setControllerAdvice(new BotExecutionPreflightExceptionHandler())
                .build();
    }

    @Test
    void exposesTheCompletePreflightReport() throws Exception {
        mvc.perform(get("/api/v1/bots/{botId}/preflight", BOT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.botId").value(BOT_ID.toString()))
                .andExpect(jsonPath("$.ready").value(false))
                .andExpect(jsonPath("$.issues[0].code").value("CONCURRENT_EXECUTION_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.issues[1].code").value("UNSUPPORTED_INSTRUMENT"));
    }

    @Test
    void returnsNotFoundWithoutLeakingOwnership() throws Exception {
        mvc.perform(get("/api/v1/bots/{botId}/preflight", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Bot not found"));
    }
}
