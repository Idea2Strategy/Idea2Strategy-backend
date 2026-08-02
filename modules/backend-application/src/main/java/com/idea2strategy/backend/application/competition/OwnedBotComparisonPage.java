package com.idea2strategy.backend.application.competition;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OwnedBotComparisonPage(
        UUID snapshotId,
        String snapshotStatus,
        Instant cutoffAt,
        List<OwnedBotComparisonItem> items,
        String nextCursor,
        boolean hasMore) {
    public OwnedBotComparisonPage {
        items = List.copyOf(items);
    }

    public static OwnedBotComparisonPage empty() {
        return new OwnedBotComparisonPage(null, null, null, List.of(), null, false);
    }
}
