package com.idea2strategy.backend.application.identity;

import java.util.Objects;
import java.util.UUID;

public record VerifyEmailCommand(String verificationToken, UUID correlationId) {
    public VerifyEmailCommand {
        if (Objects.requireNonNull(verificationToken, "verificationToken").isBlank()) {
            throw new IllegalArgumentException("Verification token is required");
        }
        Objects.requireNonNull(correlationId, "correlationId");
    }
}
