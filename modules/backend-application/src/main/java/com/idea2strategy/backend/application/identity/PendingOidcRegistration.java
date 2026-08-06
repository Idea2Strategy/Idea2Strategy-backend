package com.idea2strategy.backend.application.identity;

import com.idea2strategy.backend.domain.identity.AccountPreferences;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PendingOidcRegistration(
        UUID accountId,
        UUID loginIdentityId,
        short providerId,
        ProtectedOidcSubject subject,
        ProtectedEmail email,
        UUID correlationId,
        Instant registeredAt,
        AccountPreferences preferences) {
    public PendingOidcRegistration {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(loginIdentityId, "loginIdentityId");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(registeredAt, "registeredAt");
        Objects.requireNonNull(preferences, "preferences");
        if (providerId < 1) throw new IllegalArgumentException("OIDC provider is required");
    }
}
