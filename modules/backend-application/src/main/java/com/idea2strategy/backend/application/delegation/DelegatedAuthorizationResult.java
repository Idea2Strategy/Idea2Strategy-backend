package com.idea2strategy.backend.application.delegation;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record DelegatedAuthorizationResult(
        UUID authorizationId,
        long authorizationVersion,
        DelegatedAuthorizationStatus status,
        UUID credentialId,
        Instant expiresAt,
        Optional<String> rawCredential) {
    public DelegatedAuthorizationResult {
        Objects.requireNonNull(authorizationId, "authorizationId");
        Objects.requireNonNull(status, "status");
        rawCredential = Objects.requireNonNull(rawCredential, "rawCredential");
        if (authorizationVersion < 1) {
            throw new IllegalArgumentException("authorizationVersion must be positive");
        }
    }

    public DelegatedAuthorizationResult withRawCredential(String rawValue) {
        return new DelegatedAuthorizationResult(
                authorizationId, authorizationVersion, status, credentialId, expiresAt,
                Optional.of(Objects.requireNonNull(rawValue, "rawValue")));
    }
}
