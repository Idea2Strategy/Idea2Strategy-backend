package com.idea2strategy.backend.migration;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class OperatorTrustMigrationContractTest {
    @Test
    void pinsVersionedMappingAndImmutableBootstrapReceipt() throws Exception {
        String sql;
        try (var input = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V20260802232000__backend_operator_trust.sql")) {
            assertTrue(input != null, "operator trust migration must exist");
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String fragment : new String[] {
                "external_identity_key_version",
                "operator_identity_key_version_positive",
                "require_versioned_operator_identity",
                "operations.operator_bootstrap_receipts",
                "operator_bootstrap_key_version_positive",
                "operator_role_assignment_id",
                "audit_event_id",
                "require_coherent_operator_bootstrap_receipt",
                "guard_operator_bootstrap_receipt_immutable"
        }) assertTrue(sql.contains(fragment), "missing migration contract: " + fragment);
    }
}
