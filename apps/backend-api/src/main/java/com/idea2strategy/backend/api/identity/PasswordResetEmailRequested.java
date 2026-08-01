package com.idea2strategy.backend.api.identity;

import java.time.Instant;
import java.util.UUID;

public record PasswordResetEmailRequested(UUID accountId, String resetToken, Instant expiresAt) {
    @Override
    public String toString() {
        return "PasswordResetEmailRequested[accountId=" + accountId + ", token=REDACTED, expiresAt=" + expiresAt + "]";
    }
}
