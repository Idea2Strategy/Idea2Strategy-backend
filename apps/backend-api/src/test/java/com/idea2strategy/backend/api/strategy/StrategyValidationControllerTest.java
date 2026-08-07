package com.idea2strategy.backend.api.strategy;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
        when(validationService.validate(any(), any())).thenReturn(new StrategyValidationRun(
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
                        "BACKTEST_BLOCK_UNSUPPORTED",
                        "groups[0]",
                        "Element is not reproducible in backtests",
                        List.of("ORDER_EVENT_HISTORY"))),
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

        // Validation is asked only for the document and its catalog; availability is not its question.
        verify(validationService).validate(org.mockito.ArgumentMatchers.eq(STRATEGY_ID),
                org.mockito.ArgumentMatchers.same(catalog));
    }

    @Test
    void previewsAnUnsavedEditorRevisionAndEchoesItsRevisionForStaleResponseProtection() throws Exception {
        var validationService = mock(BasicStrategyValidationCommandService.class);
        var catalogService = mock(BasicStrategyCatalogQueryService.class);
        var catalog = mock(BasicStrategyCatalog.class);
        var version = mock(ElementCatalogVersion.class);
        when(version.dataRequirementVersion()).thenReturn("alpaca-sip/v1");
        when(catalog.version()).thenReturn(version);
        when(catalogService.getPublished(CATALOG_ID)).thenReturn(catalog);
        when(validationService.preview(any(), any(), any(), org.mockito.ArgumentMatchers.eq(9L)))
                .thenReturn(new StrategyValidationRun(
                        VALIDATION_ID, STRATEGY_ID, OWNER_ID, null, 9, "b".repeat(64), CATALOG_ID,
                        StrategyValidationStatus.INVALID,
                        List.of(new StrategyValidationFinding(
                                StrategyValidationFinding.Severity.BLOCKING_ERROR,
                                "REQUIRED_PARAMETER_MISSING", "groups[0].blocks[0].parameters.threshold",
                                "Required parameter is missing", List.of())),
                        NOW, NOW));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                        new StrategyValidationController(validationService, catalogService))
                .setControllerAdvice(new StrategyAuthoringExceptionHandler())
                .build();

        mvc.perform(post("/api/v1/strategies/{strategyId}/validation-previews", STRATEGY_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"catalogId":"%s","clientRevision":9,
                                 "semanticDocument":{"mode":"BASIC","catalogId":"%s","groups":[]}}
                                """.formatted(CATALOG_ID, CATALOG_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestedEditSequence").value(9))
                .andExpect(jsonPath("$.status").value("INVALID"))
                .andExpect(jsonPath("$.findings[0].code").value("REQUIRED_PARAMETER_MISSING"));

        verify(validationService).preview(
                org.mockito.ArgumentMatchers.eq(STRATEGY_ID),
                org.mockito.ArgumentMatchers.same(catalog),
                any(String.class),
                org.mockito.ArgumentMatchers.eq(9L));
    }
}
