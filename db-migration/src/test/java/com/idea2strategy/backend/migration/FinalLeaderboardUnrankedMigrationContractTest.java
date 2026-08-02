package com.idea2strategy.backend.migration;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class FinalLeaderboardUnrankedMigrationContractTest {
    @Test
    void permitsAbsentRankAndScoreWithoutRewritingExistingResults() throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream(
                "db/migration/V20260802194500__backend_final_leaderboard_unranked_entries.sql")) {
            var sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
            assertTrue(sql.contains("alter column rank drop not null"));
            assertTrue(sql.contains("alter column score drop not null"));
            assertTrue(!sql.contains("update competition.leaderboard_entries"));
        }
    }
}
