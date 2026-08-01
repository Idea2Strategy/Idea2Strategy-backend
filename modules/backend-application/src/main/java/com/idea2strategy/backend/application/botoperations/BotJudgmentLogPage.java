package com.idea2strategy.backend.application.botoperations;

import java.util.List;

public record BotJudgmentLogPage(
        List<BotJudgmentLogEntry> entries, long nextAfterSequence, boolean hasMore) {
    public BotJudgmentLogPage {
        entries = List.copyOf(entries);
    }
}
