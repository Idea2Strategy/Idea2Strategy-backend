package com.idea2strategy.backend.application.identity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PendingOidcLink(
        UUID id,
        UUID accountId,
        UUID reauthenticatedLoginIdentityId,
        short providerId,
        String subjectHmac,
        short subjectKeyVersion,
        UUID correlationId,
        Instant requestedAt) {
    public PendingOidcLink {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(reauthenticatedLoginIdentityId, "reauthenticatedLoginIdentityId");
        Objects.requireNonNull(subjectHmac, "subjectHmac");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(requestedAt, "requestedAt");
        if (providerId < 1 || subjectHmac.isBlank() || subjectKeyVersion < 1) {
            throw new IllegalArgumentException("Pending OIDC link fields must be present");
        }
    }
}
