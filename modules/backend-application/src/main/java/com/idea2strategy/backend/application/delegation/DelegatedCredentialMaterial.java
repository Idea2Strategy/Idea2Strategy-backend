package com.idea2strategy.backend.application.delegation;

import java.util.Objects;

public record DelegatedCredentialMaterial(String rawValue, String digest, short digestKeyVersion) {
    public DelegatedCredentialMaterial {
        if (Objects.requireNonNull(rawValue, "rawValue").isBlank()
                || Objects.requireNonNull(digest, "digest").isBlank()
                || digestKeyVersion < 1) {
            throw new IllegalArgumentException("credential material must be complete");
        }
    }
}
