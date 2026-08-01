package com.idea2strategy.backend.application.identity;

import java.util.Objects;
import java.util.UUID;

public record ResendVerificationCommand(UUID accountId, UUID correlationId, String requestIpPrefix) {
    public ResendVerificationCommand {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(correlationId, "correlationId");
    }
}
