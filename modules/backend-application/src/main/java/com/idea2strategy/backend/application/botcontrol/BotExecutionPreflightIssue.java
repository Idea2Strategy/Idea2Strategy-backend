package com.idea2strategy.backend.application.botcontrol;

import java.util.Objects;

public record BotExecutionPreflightIssue(String code, String detail) {
    public BotExecutionPreflightIssue {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(detail, "detail");
        if (code.isBlank() || detail.isBlank()) {
            throw new IllegalArgumentException("Preflight issue code and detail must not be blank");
        }
    }
}
