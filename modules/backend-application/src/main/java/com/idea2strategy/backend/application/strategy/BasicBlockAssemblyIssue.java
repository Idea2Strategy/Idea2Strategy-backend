package com.idea2strategy.backend.application.strategy;

import java.util.Objects;

public record BasicBlockAssemblyIssue(String code, String location, String message) {
    public BasicBlockAssemblyIssue {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(message, "message");
    }
}
