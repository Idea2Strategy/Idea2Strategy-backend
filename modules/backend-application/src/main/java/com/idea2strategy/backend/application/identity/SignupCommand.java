package com.idea2strategy.backend.application.identity;

import java.util.Objects;
import java.util.UUID;

public record SignupCommand(String email, String password, UUID correlationId, String requestIpPrefix) {
    public SignupCommand {
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(correlationId, "correlationId");
    }

    @Override
    public String toString() {
        return "SignupCommand[credentials=REDACTED, correlationId=" + correlationId + "]";
    }
}
