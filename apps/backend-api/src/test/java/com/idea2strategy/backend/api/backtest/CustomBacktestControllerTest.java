package com.idea2strategy.backend.api.backtest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idea2strategy.backend.application.backtest.BacktestRequestReceipt;
import com.idea2strategy.backend.application.backtest.CustomBacktestService;
import com.idea2strategy.backend.application.strategy.ImmutableStrategyReleaseRejectedException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CustomBacktestControllerTest {
    @Test
    void acceptsOnlyTheUserPeriodAndLeavesOfficialInputSelectionToTheServer() throws Exception {
        var service = mock(CustomBacktestService.class);
        UUID messageId = UUID.fromString("98000000-0000-4000-8000-000000000001");
        when(service.request(any())).thenReturn(
                new BacktestRequestReceipt(messageId, "CUSTOM_BACKTEST_REQUESTED", true));
        var mvc = MockMvcBuilders.standaloneSetup(new CustomBacktestController(service)).build();

        mvc.perform(post("/api/v1/bots/98000000-0000-4000-8000-000000000002/backtests")
                        .header("Idempotency-Key", "custom-request-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"periodStart":"2024-01-01","periodEnd":"2024-12-31"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.messageId").value(messageId.toString()))
                .andExpect(jsonPath("$.eventType").value("CUSTOM_BACKTEST_REQUESTED"))
                .andExpect(jsonPath("$.created").value(true));
    }

    @Test
    void explainsWhenNoCoherentOfficialDatasetCoversTheStrategy() throws Exception {
        var service = mock(CustomBacktestService.class);
        when(service.request(any())).thenThrow(new ImmutableStrategyReleaseRejectedException(
                "No coherent official backtest dataset set covers required resolutions 30m, 1d"));
        var mvc = MockMvcBuilders.standaloneSetup(new CustomBacktestController(service))
                .setControllerAdvice(new CustomBacktestExceptionHandler())
                .build();

        mvc.perform(post("/api/v1/bots/98000000-0000-4000-8000-000000000002/backtests")
                        .header("Idempotency-Key", "custom-request-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"periodStart":"2024-01-01","periodEnd":"2024-12-31"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reasonCode").value("OFFICIAL_BACKTEST_INPUTS_UNAVAILABLE"))
                .andExpect(jsonPath("$.detail").value(
                        "No coherent official backtest dataset set covers required resolutions 30m, 1d"));
    }

    @Test
    void rejectsAMissingPeriodAsAClientError() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new CustomBacktestController(mock(CustomBacktestService.class)))
                .setControllerAdvice(new CustomBacktestExceptionHandler())
                .build();

        mvc.perform(post("/api/v1/bots/98000000-0000-4000-8000-000000000002/backtests")
                        .header("Idempotency-Key", "custom-request-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"periodEnd":"2024-12-31"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("periodStart must not be null"));
    }
}
