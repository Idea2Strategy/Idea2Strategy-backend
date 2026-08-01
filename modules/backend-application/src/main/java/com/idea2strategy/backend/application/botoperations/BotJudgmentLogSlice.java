package com.idea2strategy.backend.application.botoperations;

import java.util.List;

public record BotJudgmentLogSlice(List<BotJudgmentLogEntry> entries, boolean hasMore) {
    public BotJudgmentLogSlice {
        entries = List.copyOf(entries);
    }
}
