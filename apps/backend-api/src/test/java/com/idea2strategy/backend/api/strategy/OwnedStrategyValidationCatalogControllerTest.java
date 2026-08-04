package com.idea2strategy.backend.api.strategy;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.idea2strategy.backend.application.strategy.OwnedStrategyValidationCatalogItem;
import com.idea2strategy.backend.application.strategy.OwnedStrategyValidationCatalogQueryPort;
import com.idea2strategy.backend.application.strategy.OwnedStrategyValidationCatalogQueryService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OwnedStrategyValidationCatalogControllerTest {
    private static final UUID OWNER_ID = UUID.fromString("72000000-0000-4000-8000-000000000001");
    private static final UUID RUN_ID = UUID.fromString("72000000-0000-4000-8000-000000000002");
    private static final UUID STRATEGY_ID = UUID.fromString("72000000-0000-4000-8000-000000000003");
    private static final UUID CATALOG_ID = UUID.fromString("72000000-0000-4000-8000-000000000004");
    private static final Instant COMPLETED_AT = Instant.parse("2026-08-04T09:59:00Z");

    @Test
    void exposesOnlyCurrentValidRunsOwnedByThePrincipal() throws Exception {
        OwnedStrategyValidationCatalogQueryPort port = ownerId -> {
            if (!OWNER_ID.equals(ownerId)) {
                throw new AssertionError("query must be scoped to the current principal");
            }
            return List.of(new OwnedStrategyValidationCatalogItem(
                    RUN_ID, STRATEGY_ID, "Momentum", 7, digest('a'), CATALOG_ID, COMPLETED_AT));
        };
        var service = new OwnedStrategyValidationCatalogQueryService(port, () -> OWNER_ID);
        var mvc = MockMvcBuilders
                .standaloneSetup(new OwnedStrategyValidationCatalogController(service))
                .build();

        mvc.perform(get("/api/v1/strategy-validations/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].validationRunId").value(RUN_ID.toString()))
                .andExpect(jsonPath("$.items[0].strategyId").value(STRATEGY_ID.toString()))
                .andExpect(jsonPath("$.items[0].strategyName").value("Momentum"))
                .andExpect(jsonPath("$.items[0].requestedEditSequence").value(7))
                .andExpect(jsonPath("$.items[0].elementCatalogVersionId").value(CATALOG_ID.toString()))
                .andExpect(jsonPath("$.items[0].completedAt").value(COMPLETED_AT.toString()));
    }

    private static String digest(char value) {
        return String.valueOf(value).repeat(64);
    }
}
