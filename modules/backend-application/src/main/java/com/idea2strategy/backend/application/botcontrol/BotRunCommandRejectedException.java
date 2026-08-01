package com.idea2strategy.backend.application.botcontrol;

import java.util.List;
import java.util.Objects;

public final class BotRunCommandRejectedException extends IllegalStateException {
    private final List<BotExecutionPreflightIssue> issues;

    public BotRunCommandRejectedException(List<BotExecutionPreflightIssue> issues) {
        super("Bot execution preflight failed");
        this.issues = List.copyOf(Objects.requireNonNull(issues, "issues"));
        if (this.issues.isEmpty()) {
            throw new IllegalArgumentException("Rejected command must contain at least one issue");
        }
    }

    public List<BotExecutionPreflightIssue> issues() {
        return issues;
    }
}
