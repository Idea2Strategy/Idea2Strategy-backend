package com.idea2strategy.backend.api.identity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AccountVerificationEmailRequested(UUID accountId, String verificationToken, Instant expiresAt) {
    public AccountVerificationEmailRequested {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(verificationToken, "verificationToken");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    @Override
    public String toString() {
        return "AccountVerificationEmailRequested[accountId=" + accountId
                + ", delivery=REDACTED, expiresAt=" + expiresAt + "]";
    }
}
