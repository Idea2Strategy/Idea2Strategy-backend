package com.idea2strategy.backend.application.identity;

import java.util.Objects;

public record IdentifierFingerprint(String value, short keyVersion) {
    public IdentifierFingerprint {
        Objects.requireNonNull(value, "value");
        if (value.isBlank() || keyVersion < 1) {
            throw new IllegalArgumentException("Identifier fingerprint and key version are required");
        }
    }
}
