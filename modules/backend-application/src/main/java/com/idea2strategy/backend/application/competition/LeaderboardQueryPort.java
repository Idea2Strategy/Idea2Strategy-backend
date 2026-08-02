package com.idea2strategy.backend.application.competition;

@FunctionalInterface
public interface LeaderboardQueryPort {
    LeaderboardQueryResult query(AnonymousLeaderboardQuery query);
}
