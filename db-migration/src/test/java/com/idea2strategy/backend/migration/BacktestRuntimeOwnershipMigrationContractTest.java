package com.idea2strategy.backend.migration;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class BacktestRuntimeOwnershipMigrationContractTest {
    @Test
    void expandsThenConstrainsBacktestOwnershipWithoutInventingALegacyPolicy() throws Exception {
        String expand = migration("V20260804160000__backtest_runtime_ownership_expand.sql");
        String constrain = migration("V20260804160100__backtest_runtime_ownership_constrain.sql");

        for (String fragment : new String[] {
                "execution_policy_version", "canonical_payload_hash", "aggregate_sequence",
                "claim_token", "claim_expires_at", "last_heartbeat_at", "previous_attempt_id",
                "terminal_reason_code", "cancellation_requested_at", "legacy_execution_policy_mappings",
                "execution_policy_versions", "policy_artifact_hash", "policy_document", "locked_at"
        }) assertTrue(expand.contains(fragment), "missing expand contract: " + fragment);
        assertTrue(constrain.contains("BACKTEST_EXECUTION_POLICY_MAPPING_REQUIRED"));
        assertTrue(constrain.contains("ALTER COLUMN execution_policy_version SET NOT NULL"));
        assertTrue(constrain.contains("backtest_run_execution_policy_fk"));
        assertTrue(!expand.matches("(?s).*execution_policy_version[^;]*DEFAULT.*"),
                "execution policy must never receive a silent default");
    }

    @Test
    void preservesDatasetHashMeaningAndExemptsOnlyZeroObjectManifests() throws Exception {
        String sql = migration("V20260804160020__pipeline_dataset_manifest_empty_hash.sql");
        assertTrue(sql.contains("object_count"));
        assertTrue(sql.contains("uq_dataset_manifests_dataset_hash"));
        assertTrue(sql.contains("WHERE object_count > 0"));
        assertTrue(sql.contains("dataset_manifest_object_count_maintain"));
        assertTrue(!sql.contains("UPDATE market_data.dataset_manifests SET dataset_hash"));
    }

    private String migration(String name) throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream("db/migration/" + name)) {
            assertTrue(input != null, "migration must exist: " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
