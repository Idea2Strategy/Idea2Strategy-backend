package com.idea2strategy.backend.application.identity;

import java.util.Objects;

public record OidcIdTokenVerificationRequest(String providerCode, String idToken, String expectedNonce) {
    public OidcIdTokenVerificationRequest(String providerCode, String idToken) {
        this(providerCode, idToken, null);
    }

    public OidcIdTokenVerificationRequest {
        Objects.requireNonNull(providerCode, "providerCode");
        Objects.requireNonNull(idToken, "idToken");
        if (providerCode.isBlank() || idToken.isBlank()) {
            throw new IllegalArgumentException("OIDC ID token verification fields must be present");
        }
        if (expectedNonce != null && expectedNonce.isBlank()) {
            throw new IllegalArgumentException("Expected OIDC nonce must not be blank");
        }
    }

    @Override
    public String toString() {
        return "OidcIdTokenVerificationRequest[providerCode=" + providerCode
                + ", idToken=<redacted>, expectedNonce=<redacted>]";
    }
}
