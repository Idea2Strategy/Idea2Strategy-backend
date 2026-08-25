package com.idea2strategy.backend.migration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class BasicStrategyExecutionCompletionMigrationContractTest {
    @Test
    void migrationPublishesTheV2CatalogCapAndAllRuntimeArguments() throws Exception {
        try (var backendStream = getClass().getClassLoader().getResourceAsStream(
                        "db/migration/V20260825000000__backend_basic_strategy_execution_completion.sql");
                var pipelineStream = getClass().getClassLoader().getResourceAsStream(
                        "db/migration/V20260825000001__pipeline_basic_strategy_feature_catalog.sql")) {
            assertNotNull(backendStream);
            assertNotNull(pipelineStream);
            String sql = new String(backendStream.readAllBytes(), StandardCharsets.UTF_8)
                    + new String(pipelineStream.readAllBytes(), StandardCharsets.UTF_8);
            for (String literal : new String[] {
                    "basic-elements:2026-08-25", "maxPositionPercent",
                    "x-numericExclusiveMinimum", "x-numericMaximum",
                    "$maxPositionPercent", "market_data.feature_definitions"
            }) {
                assertTrue(sql.contains(literal), literal);
            }
        }
    }
}
