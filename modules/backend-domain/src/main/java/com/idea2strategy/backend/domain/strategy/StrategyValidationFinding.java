package com.idea2strategy.backend.domain.strategy;

import java.util.List;
import java.util.Objects;

public record StrategyValidationFinding(
        Severity severity,
        String code,
        String location,
        String message,
        List<String> requirements) {
    public StrategyValidationFinding {
        Objects.requireNonNull(severity, "severity");
        code = requireText(code, "code");
        location = requireText(location, "location");
        message = requireText(message, "message");
        requirements = List.copyOf(requirements);
    }

    public enum Severity {
        BLOCKING_ERROR,
        WARNING,
        INFORMATION
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
