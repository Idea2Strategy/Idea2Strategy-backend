package com.idea2strategy.backend.application.identity;

import java.util.Objects;

public record PasswordHash(String encodedHash, String scheme, String parametersJson) {
    public PasswordHash {
        requireText(encodedHash, "encodedHash");
        requireText(scheme, "scheme");
        requireText(parametersJson, "parametersJson");
    }

    private static void requireText(String value, String name) {
        if (Objects.requireNonNull(value, name).isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    @Override
    public String toString() {
        return "PasswordHash[REDACTED]";
    }
}
