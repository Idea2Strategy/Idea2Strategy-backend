package com.idea2strategy.backend.application.identity;

import java.util.Objects;
import java.util.UUID;

public record StartOidcLinkCommand(
        UUID accountId,
        UUID reauthenticatedLoginIdentityId,
        String providerCode,
        String issuer,
        String subject,
        String email,
        UUID correlationId) {
    public StartOidcLinkCommand {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(reauthenticatedLoginIdentityId, "reauthenticatedLoginIdentityId");
        Objects.requireNonNull(providerCode, "providerCode");
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(correlationId, "correlationId");
        if (providerCode.isBlank() || issuer.isBlank() || subject.isBlank()) {
            throw new IllegalArgumentException("OIDC link fields must be present");
        }
    }
}
