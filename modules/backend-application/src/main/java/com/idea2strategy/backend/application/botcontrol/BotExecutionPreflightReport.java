package com.idea2strategy.backend.application.botcontrol;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record BotExecutionPreflightReport(UUID botId, List<BotExecutionPreflightIssue> issues) {
    public BotExecutionPreflightReport {
        Objects.requireNonNull(botId, "botId");
        issues = List.copyOf(Objects.requireNonNull(issues, "issues"));
    }

    public boolean ready() {
        return issues.isEmpty();
    }
}
