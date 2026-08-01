package com.idea2strategy.backend.application.identity;

import java.util.Objects;

public record OidcProvider(short id, String code, String issuer, boolean active) {
    public OidcProvider {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(issuer, "issuer");
        if (id < 1 || code.isBlank() || issuer.isBlank()) {
            throw new IllegalArgumentException("OIDC provider fields must be present");
        }
    }
}
