package com.idea2strategy.backend.api.strategy;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idea2strategy.backend.application.strategy.StrategyDeletionCommandPort;
import com.idea2strategy.backend.application.strategy.StrategyDeletionCommandService;
import com.idea2strategy.backend.application.strategy.StrategyDeletionResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class StrategyDeletionControllerTest {
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final UUID STRATEGY_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-09T00:00:00Z");

    @Test
    void returnsNoContentForFirstAndRepeatedDeletion() throws Exception {
        StrategyDeletionCommandPort port = Mockito.mock(StrategyDeletionCommandPort.class);
        when(port.deleteOwned(STRATEGY_ID, OWNER_ID, NOW))
                .thenReturn(StrategyDeletionResult.DELETED, StrategyDeletionResult.ALREADY_DELETED);
        var service = new StrategyDeletionCommandService(
                port, () -> OWNER_ID, Clock.fixed(NOW, ZoneOffset.UTC));
        var mvc = MockMvcBuilders.standaloneSetup(new StrategyDeletionController(service))
                .setControllerAdvice(new StrategyAuthoringExceptionHandler())
                .build();

        mvc.perform(delete("/api/v1/strategies/{strategyId}", STRATEGY_ID))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/strategies/{strategyId}", STRATEGY_ID))
                .andExpect(status().isNoContent());
    }

    @Test
    void returnsNotFoundForAnotherOwnersStrategy() throws Exception {
        StrategyDeletionCommandPort port = Mockito.mock(StrategyDeletionCommandPort.class);
        when(port.deleteOwned(STRATEGY_ID, OWNER_ID, NOW)).thenReturn(StrategyDeletionResult.NOT_FOUND);
        var service = new StrategyDeletionCommandService(
                port, () -> OWNER_ID, Clock.fixed(NOW, ZoneOffset.UTC));
        var mvc = MockMvcBuilders.standaloneSetup(new StrategyDeletionController(service))
                .setControllerAdvice(new StrategyAuthoringExceptionHandler())
                .build();

        mvc.perform(delete("/api/v1/strategies/{strategyId}", STRATEGY_ID))
                .andExpect(status().isNotFound());
    }
}
