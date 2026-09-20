package com.idea2strategy.backend.api.strategy;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idea2strategy.backend.application.strategy.StrategyReleaseInputCatalog;
import com.idea2strategy.backend.application.strategy.StrategyReleaseInputCatalog.Dataset;
import com.idea2strategy.backend.application.strategy.StrategyReleaseInputCatalog.ExecutionPolicy;
import com.idea2strategy.backend.application.strategy.StrategyReleaseInputCatalogQueryService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class StrategyReleaseInputCatalogControllerTest {
    @Test
    void exposesOnlyServerObservedImmutableReleaseInputs() throws Exception {
        var service = mock(StrategyReleaseInputCatalogQueryService.class);
        UUID feeId = UUID.fromString("6f2eae59-bc3d-4fc2-9330-a544d4c7e101");
        UUID bufferId = UUID.fromString("a27b9962-56cc-41f8-b98c-9311833ff201");
        UUID datasetId = UUID.fromString("30000000-0000-4000-8000-000000000001");
        when(service.getSelectable()).thenReturn(new StrategyReleaseInputCatalog(
                List.of(new ExecutionPolicy(
                        "policy-v1", "market-v1", "accounting-v1", "precision-v1",
                        feeId, 20, bufferId, 1,
                        LocalDate.parse("2025-01-01"), LocalDate.parse("2026-01-01"),
                        "market-bars-v2", Instant.parse("2026-08-07T11:00:00Z"))),
                List.of(new Dataset(
                        datasetId, "alpaca-sip", "ADJUSTED", "1m", 1,
                        LocalDate.parse("2025-01-01"), LocalDate.parse("2026-01-01"), "market-bars-v2",
                        Instant.parse("2026-08-07T11:30:00Z"))),
                Instant.parse("2026-08-07T12:00:00Z")));

        MockMvcBuilders.standaloneSetup(new StrategyReleaseInputCatalogController(service))
                .build()
                .perform(get("/api/v1/strategy-release-inputs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionPolicies[0].version").value("policy-v1"))
                .andExpect(jsonPath("$.executionPolicies[0].feePolicyId").value(feeId.toString()))
                .andExpect(jsonPath("$.datasets[0].id").value(datasetId.toString()))
                .andExpect(jsonPath("$.datasets[0].periodStart").value("2025-01-01"));
    }
}
