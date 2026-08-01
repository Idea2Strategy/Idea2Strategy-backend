package com.idea2strategy.backend.application.identity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ActivateOidcLink(
        UUID accountId,
        UUID reauthenticatedLoginIdentityId,
        UUID pendingLoginIdentityId,
        short providerId,
        String subjectHmac,
        UUID correlationId,
        Instant activatedAt) {
    public ActivateOidcLink {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(reauthenticatedLoginIdentityId, "reauthenticatedLoginIdentityId");
        Objects.requireNonNull(pendingLoginIdentityId, "pendingLoginIdentityId");
        Objects.requireNonNull(subjectHmac, "subjectHmac");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(activatedAt, "activatedAt");
        if (reauthenticatedLoginIdentityId.equals(pendingLoginIdentityId)) {
            throw new IllegalArgumentException("Current and pending login identities must differ");
        }
        if (providerId < 1 || subjectHmac.isBlank()) {
            throw new IllegalArgumentException("Verified OIDC identity must be present");
        }
    }
}
