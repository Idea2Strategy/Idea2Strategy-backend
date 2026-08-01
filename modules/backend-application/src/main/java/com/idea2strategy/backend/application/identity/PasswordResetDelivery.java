package com.idea2strategy.backend.application.identity;

import java.time.Instant;
import java.util.UUID;

public record PasswordResetDelivery(UUID accountId, String rawToken, Instant expiresAt) {
    @Override
    public String toString() {
        return "PasswordResetDelivery[accountId=" + accountId + ", token=REDACTED, expiresAt=" + expiresAt + "]";
    }
}
