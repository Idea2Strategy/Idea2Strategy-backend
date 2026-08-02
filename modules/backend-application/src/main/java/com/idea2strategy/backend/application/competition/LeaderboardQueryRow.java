package com.idea2strategy.backend.application.competition;

import java.util.Objects;

public record LeaderboardQueryRow(String cursorAnchor, AnonymousLeaderboardItem item) {
    public LeaderboardQueryRow {
        Objects.requireNonNull(cursorAnchor, "cursorAnchor");
        Objects.requireNonNull(item, "item");
    }
}
