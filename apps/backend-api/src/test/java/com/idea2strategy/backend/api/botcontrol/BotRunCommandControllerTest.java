package com.idea2strategy.backend.api.botcontrol;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idea2strategy.backend.application.botcontrol.BotExecutionPreflightFacts;
import com.idea2strategy.backend.application.botcontrol.BotExecutionPreflightService;
import com.idea2strategy.backend.application.botcontrol.BotRunCommandPort;
import com.idea2strategy.backend.application.botcontrol.BotRunCommandService;
import com.idea2strategy.backend.application.botcontrol.BotRunDispatch;
import com.idea2strategy.backend.application.botcontrol.BotRunDispatchMode;
import com.idea2strategy.backend.application.common.CurrentPrincipal;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class BotRunCommandControllerTest {
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID BOT_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID MESSAGE_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-01T09:00:00Z");
    private static final Instant ROOM_START = Instant.parse("2026-08-03T13:30:00Z");

    @Test
    void acceptsAnIdempotentWaitingDispatch() throws Exception {
        CurrentPrincipal principal = () -> OWNER_ID;
        var preflight = preflight(principal, new BigDecimal("100000"), 1);
        BotRunCommandPort port = (botId, ownerId, requestedAt) -> Optional.of(new BotRunDispatch(
                BOT_ID,
                MESSAGE_ID,
                "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                ROOM_START,
                BotRunDispatchMode.WAITING,
                true));
        MockMvc mvc = mvc(new BotRunCommandService(port, preflight, principal, fixedClock()));

        mvc.perform(post("/api/v1/bots/{botId}/run", BOT_ID))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.botId").value(BOT_ID.toString()))
                .andExpect(jsonPath("$.messageId").value(MESSAGE_ID.toString()))
                .andExpect(jsonPath("$.mode").value("WAITING"))
                .andExpect(jsonPath("$.executionEligibleFrom").value(ROOM_START.toString()))
                .andExpect(jsonPath("$.created").value(true));
    }

    @Test
    void returnsAllPreflightBlockersAsAConflict() throws Exception {
        CurrentPrincipal principal = () -> OWNER_ID;
        var preflight = preflight(principal, BigDecimal.ZERO, 11);
        BotRunCommandPort port = (botId, ownerId, requestedAt) -> {
            throw new AssertionError("blocked command must not reach persistence");
        };
        MockMvc mvc = mvc(new BotRunCommandService(port, preflight, principal, fixedClock()));

        mvc.perform(post("/api/v1/bots/{botId}/run", BOT_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Bot execution preflight failed"))
                .andExpect(jsonPath("$.issues[0].code").value("INVALID_INITIAL_CAPITAL"))
                .andExpect(jsonPath("$.issues[1].code").value("CONCURRENT_EXECUTION_LIMIT_EXCEEDED"));
    }

    private static BotExecutionPreflightService preflight(
            CurrentPrincipal principal,
            BigDecimal capital,
            int projectedExecutions) {
        var facts = new BotExecutionPreflightFacts(
                BOT_ID, capital, projectedExecutions, List.of(), true, true, true, List.of());
        return new BotExecutionPreflightService(
                (botId, ownerId, at) -> Optional.of(facts), principal, fixedClock());
    }

    private static MockMvc mvc(BotRunCommandService service) {
        return MockMvcBuilders.standaloneSetup(new BotRunCommandController(service))
                .setControllerAdvice(new BotExecutionPreflightExceptionHandler())
                .build();
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }
}
