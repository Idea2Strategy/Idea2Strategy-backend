package com.idea2strategy.backend.migration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class DelegatedStrategyScopeMigrationContractTest {
    @Test
    void installsFailClosedTargetsAndAppendOnlyDerivationEvidence() throws Exception {
        String sql;
        try (var input = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V20260802231200__backend_delegated_strategy_scope.sql")) {
            assertNotNull(input);
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }

        assertTrue(sql.contains("create table identity.delegated_authorization_strategy_targets"));
        assertTrue(sql.contains("create table identity.delegated_strategy_derivations"));
        assertTrue(sql.contains("foreign key (authorization_id, source_strategy_id)"));
        assertTrue(sql.contains("references identity.delegated_authorization_strategy_targets"));
        assertTrue(sql.contains("delegated_strategy_targets_append_only"));
        assertTrue(sql.contains("delegated_strategy_derivations_append_only"));
        assertTrue(sql.contains("existing authorizations are intentionally not backfilled"));
        assertFalse(sql.contains("insert into identity.delegated_authorization_strategy_targets"));
    }
}
