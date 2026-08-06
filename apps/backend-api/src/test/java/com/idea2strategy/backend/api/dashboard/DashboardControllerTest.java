package com.idea2strategy.backend.api.dashboard;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idea2strategy.backend.application.dashboard.DashboardBotProjection;
import com.idea2strategy.backend.application.dashboard.DashboardCompetitionProjection;
import com.idea2strategy.backend.application.dashboard.DashboardPerformanceProjection;
import com.idea2strategy.backend.application.dashboard.DashboardQueryPort;
import com.idea2strategy.backend.application.dashboard.DashboardQueryService;
import com.idea2strategy.backend.domain.botcontrol.BotLifecycleStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DashboardControllerTest {
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID BOT_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID ROOM_ID = UUID.fromString("50000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-07T12:00:00Z");

    @Test
    void exposesOwnedDashboardAggregate() throws Exception {
        DashboardQueryPort port = owner -> List.of(new DashboardBotProjection(
                BOT_ID,
                "Confirmed bot",
                BotLifecycleStatus.RUNNING,
                NOW.minusSeconds(60),
                NOW,
                null,
                null,
                new DashboardPerformanceProjection(
                        new BigDecimal("10540.00"),
                        new BigDecimal("5.40"),
                        new BigDecimal("-2.10"),
                        null,
                        "performance-v1",
                        NOW.minusSeconds(30)),
                new DashboardCompetitionProjection(
                        ROOM_ID,
                        "Momentum Lab",
                        "EVALUATING",
                        "EVALUATING",
                        NOW.plusSeconds(86400),
                        "Asia/Seoul")));
        var service = new DashboardQueryService(
                port,
                () -> OWNER_ID,
                Clock.fixed(NOW, ZoneOffset.UTC));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new DashboardController(service)).build();

        mvc.perform(get("/api/v1/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generatedAt").value(NOW.toString()))
                .andExpect(jsonPath("$.bots[0].botId").value(BOT_ID.toString()))
                .andExpect(jsonPath("$.bots[0].state").value("running"))
                .andExpect(jsonPath("$.bots[0].performance.equityAmount").value(10540.0))
                .andExpect(jsonPath("$.bots[0].competition.roomName").value("Momentum Lab"));
    }
}
