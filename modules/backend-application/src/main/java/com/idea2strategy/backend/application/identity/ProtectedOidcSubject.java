package com.idea2strategy.backend.application.identity;

import java.util.Objects;

public record ProtectedOidcSubject(String hmac, short keyVersion) {
    public ProtectedOidcSubject {
        Objects.requireNonNull(hmac, "hmac");
        if (hmac.isBlank() || keyVersion < 1) {
            throw new IllegalArgumentException("Protected OIDC subject must be present");
        }
    }
}
