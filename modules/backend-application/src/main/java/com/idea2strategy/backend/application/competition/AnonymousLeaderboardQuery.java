package com.idea2strategy.backend.application.competition;

import java.util.Objects;
import java.util.UUID;

public record AnonymousLeaderboardQuery(
        UUID roomId,
        UUID viewerAccountId,
        UUID snapshotId,
        Integer afterRank,
        String afterAnchor,
        int limit) {
    public AnonymousLeaderboardQuery {
        Objects.requireNonNull(roomId, "roomId");
        if ((snapshotId == null) != (afterRank == null) || (snapshotId == null) != (afterAnchor == null)) {
            throw new IllegalArgumentException("leaderboard cursor fields must be supplied together");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
    }
}
