package com.idea2strategy.backend.migration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class OperatorRoomPermissionMigrationContractTest {
    @Test
    void installsOnlyTheApprovedPermissionCatalogWithoutImplicitRoleGrants() throws Exception {
        String sql;
        try (var input = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V20260802230000__backend_operator_room_permissions.sql")) {
            assertNotNull(input);
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }

        assertTrue(sql.contains("competition_room_read"));
        assertTrue(sql.contains("competition_room_manage"));
        assertTrue(sql.contains("on conflict (code) do nothing"));
        assertTrue(sql.contains("raise exception"));
        assertFalse(sql.contains("insert into operations.role_permissions"));
        assertFalse(sql.contains("insert into operations.operator_role_assignments"));
    }
}
