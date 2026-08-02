package com.idea2strategy.backend.application.identity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AccountLifecycleAuthenticationProof(
        AccountLifecycleAuthenticationMethod method,
        UUID accountId,
        String providerCode,
        UUID challengeId,
        Instant authenticatedAt,
        Instant verifiedAt,
        boolean active) {
    public AccountLifecycleAuthenticationProof {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(authenticatedAt, "authenticatedAt");
        Objects.requireNonNull(verifiedAt, "verifiedAt");
        if (method == AccountLifecycleAuthenticationMethod.OIDC) {
            if (providerCode == null || providerCode.isBlank() || challengeId == null) {
                throw new IllegalArgumentException("OIDC lifecycle proof must bind provider and challenge");
            }
        } else if (providerCode != null || challengeId != null) {
            throw new IllegalArgumentException("Only OIDC lifecycle proof can bind provider and challenge");
        }
    }
}
