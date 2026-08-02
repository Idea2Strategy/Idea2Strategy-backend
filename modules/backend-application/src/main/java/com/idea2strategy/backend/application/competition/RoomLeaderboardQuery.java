package com.idea2strategy.backend.application.competition;

import java.util.UUID;

public record RoomLeaderboardQuery(
        UUID roomId,
        UUID viewerAccountId,
        UUID snapshotId,
        Integer leaderboardAfterRank,
        String leaderboardAfterAnchor,
        int leaderboardLimit,
        Integer ownedAfterRank,
        String ownedAfterAnchor,
        int ownedLimit) {}
