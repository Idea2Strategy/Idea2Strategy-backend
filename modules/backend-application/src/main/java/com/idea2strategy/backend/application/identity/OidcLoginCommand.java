package com.idea2strategy.backend.application.identity;

import java.util.Objects;
import java.util.UUID;

public record OidcLoginCommand(
        String providerCode,
        String issuer,
        String subject,
        String email,
        String deviceLabel,
        UUID correlationId) {
    public OidcLoginCommand {
        Objects.requireNonNull(providerCode, "providerCode");
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(correlationId, "correlationId");
        if (providerCode.isBlank() || issuer.isBlank() || subject.isBlank()) {
            throw new IllegalArgumentException("OIDC login fields must be present");
        }
    }
}
