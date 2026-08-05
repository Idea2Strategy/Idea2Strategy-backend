package com.idea2strategy.backend.api.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idea2strategy.backend.application.strategy.BasicStrategyCatalogQueryService;
import com.idea2strategy.backend.application.strategy.ImmutableStrategyReleaseCommand;
import com.idea2strategy.backend.application.strategy.ImmutableStrategyReleaseCommandService;
import com.idea2strategy.backend.domain.strategy.ImmutableStrategyRelease;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class StrategyReleaseControllerTest {
    private static final UUID STRATEGY_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID VALIDATION_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");
    private static final UUID BOT_ID = UUID.fromString("50000000-0000-4000-8000-000000000002");
    private static final UUID FEE_ID = UUID.fromString("80000000-0000-4000-8000-000000000001");
    private static final UUID BUFFER_ID = UUID.fromString("90000000-0000-4000-8000-000000000001");
    private static final UUID DATASET_ID = UUID.fromString("a0000000-0000-4000-8000-000000000001");

    private ImmutableStrategyReleaseCommandService releaseService;
    private BasicStrategyCatalogQueryService catalogService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        releaseService = mock(ImmutableStrategyReleaseCommandService.class);
        catalogService = mock(BasicStrategyCatalogQueryService.class);
        var release = mock(ImmutableStrategyRelease.class);
        when(release.botId()).thenReturn(BOT_ID);
        when(releaseService.release(eq(STRATEGY_ID), eq(VALIDATION_ID), eq(catalogService), any()))
                .thenReturn(release);
        mvc = MockMvcBuilders.standaloneSetup(new StrategyReleaseController(releaseService, catalogService))
                .setControllerAdvice(new StrategyAuthoringExceptionHandler())
                .build();
    }

    @Test
    void releasesTheValidatedStrategyAndStartsItsOfficialBasicBacktest() throws Exception {
        mvc.perform(post("/api/v1/strategies/{strategyId}/releases", STRATEGY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "validationRunId":"30000000-0000-4000-8000-000000000001",
                                  "initialCashAmount":100000.00,
                                  "budgetCapBps":10000,
                                  "brokerRulesVersion":"broker/v1",
                                  "accountingRulesVersion":"accounting/v1",
                                  "precisionRulesVersion":"precision/v1",
                                  "feePolicyId":"80000000-0000-4000-8000-000000000001",
                                  "buyingPowerBufferPolicyId":"90000000-0000-4000-8000-000000000001",
                                  "datasetManifestId":"a0000000-0000-4000-8000-000000000001",
                                  "executionPolicyVersion":"backtest-policy-v1",
                                  "candidateConflictPolicy":{"policy":"FIRST_WINS"}
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/bots/" + BOT_ID))
                .andExpect(jsonPath("$.botId").value(BOT_ID.toString()))
                .andExpect(jsonPath("$.backtestLane").value("BASIC"));

        var command = ArgumentCaptor.forClass(ImmutableStrategyReleaseCommand.class);
        verify(releaseService).release(eq(STRATEGY_ID), eq(VALIDATION_ID), eq(catalogService), command.capture());
        assertThat(command.getValue().releaseId())
                .isEqualTo(StrategyReleaseController.releaseId(VALIDATION_ID));
        assertThat(command.getValue().initialCashAmount()).isEqualByComparingTo("100000.00");
        assertThat(command.getValue().feePolicyId()).isEqualTo(FEE_ID);
        assertThat(command.getValue().buyingPowerBufferPolicyId()).isEqualTo(BUFFER_ID);
        assertThat(command.getValue().datasetManifestId()).isEqualTo(DATASET_ID);
        assertThat(command.getValue().candidateConflictPolicy()).isEqualTo("{\"policy\":\"FIRST_WINS\"}");
    }

    @Test
    void rejectsIncompleteReleaseInputsBeforeCallingTheApplicationService() throws Exception {
        mvc.perform(post("/api/v1/strategies/{strategyId}/releases", STRATEGY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"validationRunId\":\"" + VALIDATION_ID + "\"}"))
                .andExpect(status().isBadRequest());
    }
}
