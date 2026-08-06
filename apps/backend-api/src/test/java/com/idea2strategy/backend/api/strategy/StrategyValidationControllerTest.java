package com.idea2strategy.backend.api.strategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idea2strategy.backend.application.strategy.BacktestDataCoverage;
import com.idea2strategy.backend.application.strategy.BasicStrategyCatalog;
import com.idea2strategy.backend.application.strategy.BasicStrategyCatalogQueryService;
import com.idea2strategy.backend.application.strategy.BasicStrategyValidationCommandService;
import com.idea2strategy.backend.domain.strategy.ElementCatalogVersion;
import com.idea2strategy.backend.domain.strategy.StrategyValidationFinding;
import com.idea2strategy.backend.domain.strategy.StrategyValidationRun;
import com.idea2strategy.backend.domain.strategy.StrategyValidationStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class StrategyValidationControllerTest {
    private static final UUID STRATEGY_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID VALIDATION_ID = UUID.fromString("21000000-0000-4000-8000-000000000001");
    private static final UUID CATALOG_ID = UUID.fromString("0f1a0000-0000-4000-8000-000000000001");
    private static final UUID OWNER_ID = UUID.fromString("10000000-0000-4000-8000-000000000001");
    private static final Instant NOW = Instant.parse("2026-08-07T12:00:00Z");

    @Test
    void validatesTheSavedOwnedDocumentAgainstTheRequestedPublishedCatalog() throws Exception {
        var validationService = mock(BasicStrategyValidationCommandService.class);
        var catalogService = mock(BasicStrategyCatalogQueryService.class);
        var catalog = mock(BasicStrategyCatalog.class);
        var version = mock(ElementCatalogVersion.class);
        when(version.dataRequirementVersion()).thenReturn("alpaca-sip/v1");
        when(catalog.version()).thenReturn(version);
        when(catalogService.getPublished(CATALOG_ID)).thenReturn(catalog);
        when(validationService.validate(any(), any(), any())).thenReturn(new StrategyValidationRun(
                VALIDATION_ID,
                STRATEGY_ID,
                OWNER_ID,
                null,
                3,
                "a".repeat(64),
                CATALOG_ID,
                StrategyValidationStatus.VALID,
                List.of(new StrategyValidationFinding(
                        StrategyValidationFinding.Severity.WARNING,
                        "BACKTEST_FEED_UNAVAILABLE",
                        "groups[0]",
                        "Historical coverage is unavailable",
                        List.of("feed:ADJUSTED_BAR@1m"))),
                NOW,
                NOW));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new StrategyValidationController(validationService, catalogService))
                .setControllerAdvice(new StrategyAuthoringExceptionHandler())
                .build();

        mvc.perform(post("/api/v1/strategies/{strategyId}/validations", STRATEGY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"catalogId\":\"" + CATALOG_ID + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.validationRunId").value(VALIDATION_ID.toString()))
                .andExpect(jsonPath("$.status").value("VALID"))
                .andExpect(jsonPath("$.findings[0].severity").value("WARNING"));

        var coverage = ArgumentCaptor.forClass(BacktestDataCoverage.class);
        verify(validationService).validate(org.mockito.ArgumentMatchers.eq(STRATEGY_ID),
                org.mockito.ArgumentMatchers.same(catalog), coverage.capture());
        assertThat(coverage.getValue().dataRequirementVersion()).isEqualTo("alpaca-sip/v1");
        assertThat(coverage.getValue().feeds()).isEmpty();
        assertThat(coverage.getValue().features()).isEmpty();
    }
}
