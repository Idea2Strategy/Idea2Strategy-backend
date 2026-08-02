package com.idea2strategy.backend.migration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RoomFinalAccessGrantMigrationContractTest {
    @Test
    void createsAnImmutableFinalSnapshotBoundSecretRoomGrant() throws Exception {
        String sql;
        try (var input = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V20260802213500__backend_room_final_access_grants.sql")) {
            assertNotNull(input);
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }

        assertTrue(sql.contains("create table competition.room_final_access_grants"));
        assertTrue(sql.contains("primary key (room_id, account_id)"));
        assertTrue(sql.contains("unique (snapshot_id, account_id)"));
        assertTrue(sql.contains("references competition.leaderboard_snapshots (id)"));
        assertFalse(sql.contains("on delete cascade"));
    }
}
