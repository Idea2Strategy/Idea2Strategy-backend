package com.idea2strategy.backend.application.identity;

import java.util.Objects;
import java.util.UUID;

public record ResetPasswordCommand(String resetToken, String newPassword, UUID correlationId) {
    public ResetPasswordCommand {
        Objects.requireNonNull(resetToken, "resetToken");
        Objects.requireNonNull(newPassword, "newPassword");
        Objects.requireNonNull(correlationId, "correlationId");
    }

    @Override
    public String toString() {
        return "ResetPasswordCommand[credentials=REDACTED, correlationId=" + correlationId + "]";
    }
}
