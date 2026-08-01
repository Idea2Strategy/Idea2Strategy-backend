package com.idea2strategy.backend.application.identity;

import java.util.Objects;

public record VerifiedOidcPrincipal(String providerCode, String issuer, String subject, String email) {
    public VerifiedOidcPrincipal {
        Objects.requireNonNull(providerCode, "providerCode");
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(subject, "subject");
        if (providerCode.isBlank() || issuer.isBlank() || subject.isBlank()) {
            throw new IllegalArgumentException("Verified OIDC principal fields must be present");
        }
    }

    @Override
    public String toString() {
        return "VerifiedOidcPrincipal[providerCode=" + providerCode + ", issuer=" + issuer + ", subject=<redacted>, email=<redacted>]";
    }
}
