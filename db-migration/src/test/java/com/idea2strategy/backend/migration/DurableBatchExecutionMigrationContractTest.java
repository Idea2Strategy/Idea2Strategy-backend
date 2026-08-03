package com.idea2strategy.backend.migration;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DurableBatchExecutionMigrationContractTest {
    @Test
    void pinsDurableRunClaimLeaseAttemptAndCheckpointSchema() throws Exception {
        String sql;
        try (var input = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V20260802231900__backend_durable_batch_execution.sql")) {
            assertTrue(input != null, "durable batch migration must exist");
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String fragment : new String[] {
                "operations.batch_job_versions",
                "batch_job_one_active_version",
                "operations.batch_runs",
                "operations.batch_items",
                "batch_item_stable_identity_unique",
                "claim_expires_at",
                "operations.batch_item_attempts",
                "LEASE_EXPIRED",
                "runtime_policy_version",
                "operations.batch_run_checkpoints",
                "batch_checkpoint_cursor_pair"
        }) {
            assertTrue(sql.contains(fragment), "missing migration contract: " + fragment);
        }
    }
}
