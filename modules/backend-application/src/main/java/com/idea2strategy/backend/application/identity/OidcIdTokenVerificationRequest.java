package com.idea2strategy.backend.application.identity;

import java.util.Objects;

public record OidcIdTokenVerificationRequest(String providerCode, String idToken) {
    public OidcIdTokenVerificationRequest {
        Objects.requireNonNull(providerCode, "providerCode");
        Objects.requireNonNull(idToken, "idToken");
        if (providerCode.isBlank() || idToken.isBlank()) {
            throw new IllegalArgumentException("OIDC ID token verification fields must be present");
        }
    }

    @Override
    public String toString() {
        return "OidcIdTokenVerificationRequest[providerCode=" + providerCode + ", idToken=<redacted>]";
    }
}
