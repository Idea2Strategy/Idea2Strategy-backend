package com.idea2strategy.backend.application.identity;

import com.idea2strategy.backend.domain.identity.AccountPreferences;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ActiveEmailRegistration(
        UUID accountId,
        UUID loginIdentityId,
        ProtectedEmail email,
        PasswordHash password,
        Instant registeredAt,
        UUID correlationId,
        AccountPreferences preferences) {
    public ActiveEmailRegistration {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(loginIdentityId, "loginIdentityId");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(registeredAt, "registeredAt");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(preferences, "preferences");
    }

    @Override
    public String toString() {
        return "ActiveEmailRegistration[accountId=" + accountId + ", protected=REDACTED]";
    }
}
