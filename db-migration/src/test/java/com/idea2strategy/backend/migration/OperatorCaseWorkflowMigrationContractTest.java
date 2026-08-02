package com.idea2strategy.backend.migration;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class OperatorCaseWorkflowMigrationContractTest {
    @Test
    void pinsAssignmentEventReceiptAndAppendOnlyBoundaries() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V20260802231700__backend_operator_case_workflow.sql")) {
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            for (String fragment : new String[] {
                    "assignee_operator_id", "operator_case_command_receipts",
                    "case_event_id", "request_hash", "audit_document",
                    "operator_case_command_receipts_append_only", "SANCTION_APPLIED"
            }) assertTrue(sql.contains(fragment), "missing operator case contract: " + fragment);
        }
    }
}
