package com.idea2strategy.backend.application.competition;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LeaderboardQueryResult(
        UUID snapshotId,
        String snapshotStatus,
        Instant cutoffAt,
        List<LeaderboardQueryRow> rows) {
    public LeaderboardQueryResult {
        rows = List.copyOf(rows);
    }

    public static LeaderboardQueryResult empty() {
        return new LeaderboardQueryResult(null, null, null, List.of());
    }
}
