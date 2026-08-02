package com.idea2strategy.backend.application.competition;

import java.util.List;

public record RoomLeaderboardQueryResult(
        RoomLeaderboardSummary room,
        List<ViewerParticipationState> viewerParticipations,
        LeaderboardQueryResult leaderboard,
        LeaderboardQueryResult ownedBots) {
    public RoomLeaderboardQueryResult {
        viewerParticipations = List.copyOf(viewerParticipations);
    }

    public static RoomLeaderboardQueryResult empty() {
        return new RoomLeaderboardQueryResult(
                null, List.of(), LeaderboardQueryResult.empty(), LeaderboardQueryResult.empty());
    }
}
