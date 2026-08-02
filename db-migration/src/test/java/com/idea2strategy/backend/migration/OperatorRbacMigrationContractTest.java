package com.idea2strategy.backend.migration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class OperatorRbacMigrationContractTest {
    @Test
    void pinsVersionedCatalogAssignmentEvidenceAndImmutabilityWithoutProductSeeds() throws Exception {
        String sql;
        try (var input = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V20260802231400__backend_operator_rbac.sql")) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String fragment : new String[] {
                "CREATE EXTENSION IF NOT EXISTS pgcrypto",
                "operations.rbac_catalog_versions", "rbac_catalog_one_active",
                "operations.rbac_catalog_role_permissions", "delegable boolean",
                "operator_assignment_catalog_role_fk", "request_document",
                "resolved_rbac_catalog_version", "audit_operator_rbac_evidence_complete",
                "request_hash = encode(digest(request_document::text, 'sha256'), 'hex')",
                "BEFORE INSERT OR UPDATE OR DELETE",
                "require_active_assignment_catalog_before_insert",
                "guard_operator_rbac_audit_before_change"
        }) assertTrue(sql.contains(fragment), "missing migration contract: " + fragment);
        assertFalse(sql.matches("(?s).*INSERT\\s+INTO\\s+operations\\.rbac_catalog_.*"),
                "migration must not seed product catalog values");
    }
}
