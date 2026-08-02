package com.idea2strategy.backend.migration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class UserCaseMigrationContractTest {
    private static final String RESOURCE =
            "db/migration/V20260802231300__backend_user_case_contract.sql";

    @Test
    void installsTypedHeadedAppendOnlyCasesWithReceiptsAndOwnershipProof() throws Exception {
        String sql;
        try (var stream = getClass().getClassLoader().getResourceAsStream(RESOURCE)) {
            assertNotNull(stream, RESOURCE);
            sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String fragment : new String[] {
            "CREATE TYPE operations.case_type", "case_version bigint", "last_case_event_id uuid",
            "CREATE TABLE operations.case_command_receipts",
            "CREATE TABLE operations.case_evidence_references", "case_event_chain_start_valid",
            "DEFERRABLE INITIALLY DEFERRED", "reject_case_append_only_mutation",
            "owner_account_id = account_id"
        }) {
            assertTrue(sql.contains(fragment), "missing: " + fragment);
        }
    }
}
