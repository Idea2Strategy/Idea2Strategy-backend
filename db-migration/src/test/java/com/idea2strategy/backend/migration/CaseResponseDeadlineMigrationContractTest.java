package com.idea2strategy.backend.migration;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CaseResponseDeadlineMigrationContractTest {
    @Test
    void pinsDeadlineProjectionReceiptEventAndDueIndex() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V20260802231800__backend_case_response_deadline.sql")) {
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            for (String fragment : new String[] {
                    "INFORMATION_RESPONSE_DEADLINE_EXPIRED", "response_deadline_at",
                    "deadline_policy_version", "case_deadline_receipts",
                    "ALREADY_TRANSITIONED", "case_response_deadline_due_idx",
                    "case_deadline_receipts_append_only"
            }) assertTrue(sql.contains(fragment), "missing deadline contract: " + fragment);
        }
    }
}
