package com.idea2strategy.backend.application.identity;

import java.util.Objects;

public record PasswordResetToken(String rawToken, String digest) {
    public PasswordResetToken {
        Objects.requireNonNull(rawToken, "rawToken");
        Objects.requireNonNull(digest, "digest");
    }

    @Override
    public String toString() {
        return "PasswordResetToken[REDACTED]";
    }
}
