package com.idea2strategy.backend.application.identity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.List;

public record PendingOidcLink(
        UUID id,
        UUID accountId,
        UUID reauthenticatedLoginIdentityId,
        short providerId,
        String subjectHmac,
        short subjectKeyVersion,
        UUID correlationId,
        Instant requestedAt,
        List<IdentifierFingerprint> comparisonFingerprints) {
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
        comparisonFingerprints = List.copyOf(Objects.requireNonNull(comparisonFingerprints, "comparisonFingerprints"));
    }

    public PendingOidcLink(UUID id, UUID accountId, UUID reauthenticatedLoginIdentityId,
                           short providerId, String subjectHmac, short subjectKeyVersion,
                           UUID correlationId, Instant requestedAt) {
        this(id, accountId, reauthenticatedLoginIdentityId, providerId, subjectHmac,
                subjectKeyVersion, correlationId, requestedAt,
                List.of(new IdentifierFingerprint(subjectHmac, subjectKeyVersion)));
    }
}
