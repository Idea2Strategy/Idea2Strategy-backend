package com.idea2strategy.backend.application.identity;

import java.util.Objects;

public record RefreshTokenSecret(String rawToken, String digest) {
    public RefreshTokenSecret {
        if (Objects.requireNonNull(rawToken, "rawToken").isBlank()
                || Objects.requireNonNull(digest, "digest").isBlank()) {
            throw new IllegalArgumentException("Refresh token secret values must not be blank");
        }
    }

    @Override
    public String toString() {
        return "RefreshTokenSecret[REDACTED]";
    }
}
