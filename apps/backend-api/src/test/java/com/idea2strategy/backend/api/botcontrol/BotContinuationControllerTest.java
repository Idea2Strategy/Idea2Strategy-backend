package com.idea2strategy.backend.api.botcontrol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idea2strategy.backend.application.botcontrol.BotContinuationCommandPort;
import com.idea2strategy.backend.application.botcontrol.BotContinuationFacts;
import com.idea2strategy.backend.application.botcontrol.BotContinuationQueryPort;
import com.idea2strategy.backend.application.botcontrol.BotContinuationService;
import com.idea2strategy.backend.application.common.CurrentPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class BotContinuationControllerTest {
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID BOT_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-02T09:00:00Z");
    private static final Instant DUE_AT = Instant.parse("2026-08-08T09:00:00Z");

    @Test
    void registrationUsesDeployPropertiesInsteadOfBeanDiscoveryOrder() {
        assertThat(BotContinuationController.class.getAnnotation(ConditionalOnBean.class)).isNull();
        assertThat(BotContinuationController.class.getAnnotation(ConditionalOnProperty.class)).isNotNull();
    }

    @Test
    void exposesTheCurrentWindowAndAcceptsExplicitRenewal() throws Exception {
        CurrentPrincipal principal = () -> OWNER_ID;
        BotContinuationQueryPort query = (botId, ownerId) -> Optional.of(
                new BotContinuationFacts(BOT_ID, DUE_AT, null));
        BotContinuationCommandPort command = (botId, ownerId, receivedAt) -> Optional.of(
                new BotContinuationFacts(BOT_ID, receivedAt.plusSeconds(30L * 24 * 60 * 60), receivedAt));
        var service = new BotContinuationService(query, command, principal, fixedClock());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new BotContinuationController(service))
                .setControllerAdvice(new BotExecutionPreflightExceptionHandler())
                .build();

        mvc.perform(get("/api/v1/bots/{botId}/continuation", BOT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dueAt").value(DUE_AT.toString()))
                .andExpect(jsonPath("$.renewalAvailableFrom").value("2026-08-01T09:00:00Z"))
                .andExpect(jsonPath("$.renewalAllowed").value(true));

        mvc.perform(post("/api/v1/bots/{botId}/continuation/renew", BOT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dueAt").value("2026-09-01T09:00:00Z"))
                .andExpect(jsonPath("$.lastRenewedAt").value(NOW.toString()))
                .andExpect(jsonPath("$.renewalAllowed").value(false));
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }
}
