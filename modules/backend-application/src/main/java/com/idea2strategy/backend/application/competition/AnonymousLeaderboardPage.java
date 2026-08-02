package com.idea2strategy.backend.application.competition;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AnonymousLeaderboardPage(
        UUID snapshotId,
        String snapshotStatus,
        Instant cutoffAt,
        List<AnonymousLeaderboardItem> items,
        String nextCursor,
        boolean hasMore) {
    public AnonymousLeaderboardPage {
        items = List.copyOf(items);
    }

    public static AnonymousLeaderboardPage empty() {
        return new AnonymousLeaderboardPage(null, null, null, List.of(), null, false);
    }
}
