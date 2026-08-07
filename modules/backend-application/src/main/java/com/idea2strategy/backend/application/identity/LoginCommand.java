package com.idea2strategy.backend.application.identity;

import java.util.Objects;
import java.util.UUID;

public record LoginCommand(String email, String password, UUID correlationId) {
    public LoginCommand {
        if (Objects.requireNonNull(email, "email").isBlank()
                || Objects.requireNonNull(password, "password").isBlank()) {
            throw new IllegalArgumentException("Email and password are required");
        }
        Objects.requireNonNull(correlationId, "correlationId");
    }

    @Override
    public String toString() {
        return "LoginCommand[credentials=REDACTED, correlationId=" + correlationId + "]";
    }
}
