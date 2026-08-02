package com.idea2strategy.backend.application.identity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record OidcStepUpChallenge(UUID id, String providerCode, String nonce, Instant expiresAt) {
    public OidcStepUpChallenge {
        Objects.requireNonNull(id, "id");
        if (Objects.requireNonNull(providerCode, "providerCode").isBlank()
                || Objects.requireNonNull(nonce, "nonce").isBlank()) {
            throw new IllegalArgumentException("OIDC step-up challenge must be complete");
        }
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    @Override
    public String toString() {
        return "OidcStepUpChallenge[id=" + id + ",providerCode=" + providerCode
                + ",nonce=REDACTED,expiresAt=" + expiresAt + "]";
    }
}
