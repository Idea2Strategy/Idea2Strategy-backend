package com.idea2strategy.backend.application.identity;

import java.util.Objects;

public record VerificationToken(String rawToken, String digest) {
    public VerificationToken {
        if (Objects.requireNonNull(rawToken, "rawToken").isBlank()
                || Objects.requireNonNull(digest, "digest").isBlank()) {
            throw new IllegalArgumentException("Verification token values must not be blank");
        }
    }

    @Override
    public String toString() {
        return "VerificationToken[REDACTED]";
    }
}
