package com.idea2strategy.backend.api.botoperations;

import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idea2strategy.backend.application.botoperations.BotDeletionCommandPort;
import com.idea2strategy.backend.application.botoperations.BotDeletionCommandService;
import com.idea2strategy.backend.application.botoperations.BotDeletionResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

class BotDeletionControllerTest {
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID BOT_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID ACTIVE_BOT_ID = UUID.fromString("30000000-0000-4000-8000-000000000002");
    private static final Instant NOW = Instant.parse("2026-08-09T00:00:00Z");

    @Test
    void registrationUsesDeployPropertiesInsteadOfBeanDiscoveryOrder() {
        assertThat(BotDeletionController.class.getAnnotation(ConditionalOnBean.class)).isNull();
        assertThat(BotDeletionController.class.getAnnotation(ConditionalOnProperty.class)).isNotNull();
    }

    @Test
    void deletesAStoppedBotAndRejectsAnActiveBot() throws Exception {
        BotDeletionCommandPort port = Mockito.mock(BotDeletionCommandPort.class);
        when(port.deleteOwnedStopped(BOT_ID, OWNER_ID, NOW)).thenReturn(BotDeletionResult.DELETED);
        when(port.deleteOwnedStopped(ACTIVE_BOT_ID, OWNER_ID, NOW)).thenReturn(BotDeletionResult.NOT_STOPPED);
        var service = new BotDeletionCommandService(port, () -> OWNER_ID, Clock.fixed(NOW, ZoneOffset.UTC));
        var mvc = MockMvcBuilders.standaloneSetup(new BotDeletionController(service))
                .setControllerAdvice(new BotOperationsExceptionHandler())
                .build();

        mvc.perform(delete("/api/v1/bots/{botId}", BOT_ID))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/bots/{botId}", ACTIVE_BOT_ID))
                .andExpect(status().isConflict());
    }

    @Test
    void returnsNotFoundForAnotherOwnersBot() throws Exception {
        BotDeletionCommandPort port = Mockito.mock(BotDeletionCommandPort.class);
        when(port.deleteOwnedStopped(BOT_ID, OWNER_ID, NOW)).thenReturn(BotDeletionResult.NOT_FOUND);
        var service = new BotDeletionCommandService(port, () -> OWNER_ID, Clock.fixed(NOW, ZoneOffset.UTC));
        var mvc = MockMvcBuilders.standaloneSetup(new BotDeletionController(service))
                .setControllerAdvice(new BotOperationsExceptionHandler())
                .build();

        mvc.perform(delete("/api/v1/bots/{botId}", BOT_ID))
                .andExpect(status().isNotFound());
    }
}
