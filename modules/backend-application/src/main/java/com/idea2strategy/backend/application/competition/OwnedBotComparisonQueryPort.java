package com.idea2strategy.backend.application.competition;

@FunctionalInterface
public interface OwnedBotComparisonQueryPort {
    LeaderboardQueryResult queryOwned(AnonymousLeaderboardQuery query);
}
