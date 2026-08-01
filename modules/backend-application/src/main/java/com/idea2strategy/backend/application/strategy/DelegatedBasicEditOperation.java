package com.idea2strategy.backend.application.strategy;

import java.util.Map;
import java.util.Objects;

public record DelegatedBasicEditOperation(String action, Map<String, Object> arguments) {
    public DelegatedBasicEditOperation {
        Objects.requireNonNull(action, "action");
        if (action.isBlank()) {
            throw new IllegalArgumentException("action must not be blank");
        }
        arguments = Map.copyOf(Objects.requireNonNull(arguments, "arguments"));
    }
}
