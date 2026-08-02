package com.idea2strategy.backend.application.identity;

import java.time.Instant;
import java.util.Objects;

public record AccountLifecycleAuthenticationProof(
        AccountLifecycleAuthenticationMethod method,
        Instant authenticatedAt,
        boolean active) {
    public AccountLifecycleAuthenticationProof {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(authenticatedAt, "authenticatedAt");
    }
}
