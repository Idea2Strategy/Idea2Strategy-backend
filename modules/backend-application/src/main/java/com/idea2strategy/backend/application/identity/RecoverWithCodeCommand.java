package com.idea2strategy.backend.application.identity;

import java.util.Objects;
import java.util.UUID;

public record RecoverWithCodeCommand(String email, String recoveryCode, String newPassword, UUID correlationId) {
    public RecoverWithCodeCommand {
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(recoveryCode, "recoveryCode");
        Objects.requireNonNull(newPassword, "newPassword");
        Objects.requireNonNull(correlationId, "correlationId");
    }

    @Override
    public String toString() {
        return "RecoverWithCodeCommand[credentials=REDACTED, correlationId=" + correlationId + "]";
    }
}
