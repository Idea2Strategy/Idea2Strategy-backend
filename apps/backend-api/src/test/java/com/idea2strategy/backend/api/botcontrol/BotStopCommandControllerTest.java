package com.idea2strategy.backend.api.botcontrol;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idea2strategy.backend.application.botcontrol.BotStopCommandPort;
import com.idea2strategy.backend.application.botcontrol.BotStopCommandService;
import com.idea2strategy.backend.application.botcontrol.BotStopDispatch;
import com.idea2strategy.backend.application.common.CurrentPrincipal;
import com.idea2strategy.backend.domain.botcontrol.BotLifecycleStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class BotStopCommandControllerTest {
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID BOT_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID MESSAGE_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-02T09:00:00Z");

    @Test
    void acceptsAnIdempotentStopDispatch() throws Exception {
        CurrentPrincipal principal = () -> OWNER_ID;
        BotStopCommandPort port = (botId, ownerId, reasonCode, requestedAt) -> Optional.of(new BotStopDispatch(
                BOT_ID,
                MESSAGE_ID,
                "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                BotLifecycleStatus.STOPPING,
                reasonCode,
                true));
        var service = new BotStopCommandService(port, principal, fixedClock());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new BotStopCommandController(service))
                .setControllerAdvice(new BotExecutionPreflightExceptionHandler())
                .build();

        mvc.perform(post("/api/v1/bots/{botId}/stop", BOT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"USER_REQUESTED\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.botId").value(BOT_ID.toString()))
                .andExpect(jsonPath("$.lifecycleStatus").value("STOPPING"))
                .andExpect(jsonPath("$.reasonCode").value("USER_REQUESTED"))
                .andExpect(jsonPath("$.created").value(true));
    }

    @Test
    void rejectsAnInvalidReasonWithoutIssuingACommand() throws Exception {
        CurrentPrincipal principal = () -> OWNER_ID;
        BotStopCommandPort port = (botId, ownerId, reasonCode, requestedAt) -> {
            throw new AssertionError("invalid command must not reach persistence");
        };
        var service = new BotStopCommandService(port, principal, fixedClock());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new BotStopCommandController(service))
                .setControllerAdvice(new BotExecutionPreflightExceptionHandler())
                .build();

        mvc.perform(post("/api/v1/bots/{botId}/stop", BOT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"arbitrary prose\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail")
                        .value("reasonCode must be an uppercase code of at most 80 characters"));
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }
}
