package com.idea2strategy.backend.application.competition;

import java.util.List;

public record RoomLeaderboardPage(
        RoomLeaderboardSummary room,
        List<ViewerParticipationState> viewerParticipations,
        AnonymousLeaderboardPage leaderboard,
        OwnedBotComparisonPage myBots) {
    public RoomLeaderboardPage {
        viewerParticipations = List.copyOf(viewerParticipations);
    }

    public static RoomLeaderboardPage empty() {
        return new RoomLeaderboardPage(
                null, List.of(), AnonymousLeaderboardPage.empty(), OwnedBotComparisonPage.empty());
    }
}
