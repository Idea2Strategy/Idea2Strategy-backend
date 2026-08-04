package com.idea2strategy.backend.migration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class BacktestRunInputPinsMigrationContractTest {

    @Test
    void addsTheRunToImmutableInputBundleJoinAndBackendProducerGrants() throws Exception {
        String migration;
        try (var input = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V20260805130000__backtest_run_input_pins.sql")) {
            assertNotNull(input);
            migration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(migration.contains("CREATE TABLE backtest.run_input_pins"));
        assertTrue(migration.contains("run_id uuid PRIMARY KEY"));
        assertTrue(migration.contains("input_bundle_id uuid NOT NULL UNIQUE"));
        assertTrue(migration.contains("input_bundle_fingerprint varchar(128) NOT NULL"));
        assertTrue(migration.contains("input_contract_version varchar(80) NOT NULL"));
        assertTrue(migration.contains("compiled_plan_checksum varchar(128) NOT NULL"));
        assertTrue(migration.contains("strategy_snapshot_hash varchar(128) NOT NULL"));
        assertTrue(migration.contains("execution_policy_version varchar(80) NOT NULL"));
        assertTrue(migration.contains("pinned_at timestamptz NOT NULL"));

        assertTrue(DatabaseAccessPolicy.allows(
                        DatabaseAccessPolicy.ApplicationRole.BACKEND,
                        DatabaseAccessPolicy.Access.INSERT,
                        "backtest",
                        "run_input_pins"));
        assertFalse(DatabaseAccessPolicy.allows(
                        DatabaseAccessPolicy.ApplicationRole.BACKEND,
                        DatabaseAccessPolicy.Access.UPDATE,
                        "backtest",
                        "run_input_pins"));
    }
}
