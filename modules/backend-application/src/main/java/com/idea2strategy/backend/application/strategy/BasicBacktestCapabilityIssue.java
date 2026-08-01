package com.idea2strategy.backend.application.strategy;

import java.util.List;
import java.util.Objects;

public record BasicBacktestCapabilityIssue(
        String code,
        String location,
        String message,
        List<String> requirements) {
    public BasicBacktestCapabilityIssue {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(message, "message");
        requirements = List.copyOf(requirements);
    }
}
