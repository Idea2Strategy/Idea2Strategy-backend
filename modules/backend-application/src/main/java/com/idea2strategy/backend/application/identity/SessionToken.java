package com.idea2strategy.backend.application.identity;

import java.util.Objects;

public record SessionToken(String rawToken, String digest) {
    public SessionToken {
        if (Objects.requireNonNull(rawToken, "rawToken").isBlank()
                || Objects.requireNonNull(digest, "digest").isBlank()) {
            throw new IllegalArgumentException("Session token values must not be blank");
        }
    }

    @Override
    public String toString() {
        return "SessionToken[REDACTED]";
    }
}
