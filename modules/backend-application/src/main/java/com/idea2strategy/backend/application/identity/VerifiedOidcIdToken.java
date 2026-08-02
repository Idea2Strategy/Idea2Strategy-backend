package com.idea2strategy.backend.application.identity;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

public record VerifiedOidcIdToken(
        String providerCode,
        String issuer,
        String subject,
        Set<String> audience,
        String nonce,
        String email,
        Instant authenticatedAt,
        Instant expiresAt,
        Instant verifiedAt,
        String keyId) {
    public VerifiedOidcIdToken {
        Objects.requireNonNull(providerCode, "providerCode");
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(subject, "subject");
        audience = Set.copyOf(Objects.requireNonNull(audience, "audience"));
        Objects.requireNonNull(nonce, "nonce");
        Objects.requireNonNull(authenticatedAt, "authenticatedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(verifiedAt, "verifiedAt");
        Objects.requireNonNull(keyId, "keyId");
        if (providerCode.isBlank()
                || issuer.isBlank()
                || subject.isBlank()
                || audience.isEmpty()
                || nonce.isBlank()
                || keyId.isBlank()) {
            throw new IllegalArgumentException("Verified OIDC ID token fields must be present");
        }
    }

    public VerifiedOidcPrincipal principal() {
        return new VerifiedOidcPrincipal(providerCode, issuer, subject, email);
    }

    @Override
    public String toString() {
        return "VerifiedOidcIdToken[providerCode=" + providerCode + ", issuer=" + issuer
                + ", subject=<redacted>, audience=" + audience + ", nonce=<redacted>, email=<redacted>, authenticatedAt="
                + authenticatedAt + ", expiresAt=" + expiresAt + ", verifiedAt=" + verifiedAt + ", keyId=" + keyId + "]";
    }
}
