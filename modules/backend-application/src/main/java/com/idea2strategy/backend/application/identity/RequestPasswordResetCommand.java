package com.idea2strategy.backend.application.identity;

import java.util.Objects;
import java.util.UUID;

public record RequestPasswordResetCommand(String email, UUID correlationId, String requestIpPrefix) {
    public RequestPasswordResetCommand {
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(correlationId, "correlationId");
    }

    @Override
    public String toString() {
        return "RequestPasswordResetCommand[email=REDACTED, correlationId=" + correlationId + "]";
    }
}
