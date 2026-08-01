package com.idea2strategy.backend.api.identity;

import java.time.Instant;
import java.util.Objects;

public record VerificationEmailRequested(String email, String verificationToken, Instant expiresAt) {
    public VerificationEmailRequested {
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(verificationToken, "verificationToken");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    @Override
    public String toString() {
        return "VerificationEmailRequested[delivery=REDACTED, expiresAt=" + expiresAt + "]";
    }
}
